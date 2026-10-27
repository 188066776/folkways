import { expect, test, type TestInfo } from "@playwright/test";
import {
  defineTask, input, javaTask, media, screen, tick, world,
  type MinecraftClient, type MinecraftServer,
} from "@izakyl/blockwright-minecraft";
import { foundColonyFast, openColonyPanelViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { frames } from "../shared/harness/bw-helpers";
import { ensureOut, outAbs } from "../shared/harness/out-paths";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";
import { checkModeLayers } from "../shared/ui/mode-layers";

type Vec = { x: number; y: number; z: number };
type Pixel = { x: number; y: number; left: number; top: number; right: number; bottom: number; width: number; height: number };
type Button = {
  kind: string; slot: number; resident: string; x: number; y: number; width: number; height: number; texture: string;
};
type ClientState = {
  combat: boolean; roster: number; selected: string[]; cachedPoints: number; cachedCommand: string;
  attackMode: boolean; replacementPending: boolean; routePending: boolean; controlPending: boolean;
  control: { request: number; accepted: boolean; operation: string; selection: string[]; groups: string[][] };
  route: { request: number; accepted: boolean; intent: string; points: Vec[]; cycleStart: number; command: string };
  state: { command: string; complete: boolean; stop: number; attackMove: boolean };
  buttons: Button[]; barHeight: number; overlay: string;
  modeLabel: string; modeHint: string; modeColors: number[]; modeBold: boolean[];
  failure: string; failureKey: string; failureUntil: number; failureRemaining: number;
};
type ServerState = {
  selection: string[]; groups: string[][]; owned: boolean[]; reserved: boolean[];
  group: string; command: string; kind: string; phase: string; result: string;
  members: string[]; memberGroups: string[]; points: Vec[]; cycleStart: number;
  complete: boolean; attackMove: boolean; stop: number; waiting: number; orders: string[];
};
type Observation = { client: ClientState; server: ServerState };
type Context = { colony: string; player: string; residents: string[] };

// Player actions under test go through input.*. The explicit rejected-packet probes below additionally
// exercise server refusal feedback; other Java tasks only arrange fixtures or observe production state.
test("RTS real pointer and keyboard inputs select, bind, queue, stop, suspend and release", async ({}, testInfo) => {
  test.setTimeout(600_000);
  let pair: E2EPair | undefined;
  const evidence: Record<string, unknown> = {
    scope: "Three residents on flat ground, real screen input, authoritative ACK and group assertions",
    unverified: ["Hostile engagement and resuming a route after combat", "Large formations and difficult terrain"],
    visualReview: "Route screenshots require human inspection; state assertions alone do not prove rendered pixels",
  };
  try {
    ensureOut("e2e");
    const run = newRunId();
    pair = await launchPair({
      config: "blockwright.toml#rts",
      server: { instance: `combat-input-server-${run}` },
      client: { instance: `combat-input-client-${run}` },
      snapshot: { entityTypes: ["folkways:resident"] },
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await mustCommand(server, "difficulty peaceful");
    await mustCommand(server, "gamerule doMobSpawning false");
    await mustCommand(server, "gamerule doDaylightCycle false");
    await mustCommand(server, "time set noon");
    await world.command(server,
      `fill ${origin.x - 19} ${origin.y - 1} ${origin.z - 19} ${origin.x + 19} ${origin.y - 1} ${origin.z + 19} minecraft:smooth_stone`);
    await world.command(server,
      `fill ${origin.x - 19} ${origin.y} ${origin.z - 19} ${origin.x + 19} ${origin.y + 6} ${origin.z + 19} minecraft:air`,
      { allowFailure: true });
    const founding = await foundColonyFast(server, client, player.name, { origin, residentCount: 3 });
    const colony = String(founding.evidence.colonyId);
    await openColonyPanelViaBook(server, client, player.name, founding.standBlock);
    await screen.dismiss(client);
    const residents = await server.run(PREPARE_RESIDENTS, { colony, ...origin });
    expect(residents).toHaveLength(3);
    const context: Context = { colony, player: player.name, residents };
    await mustCommand(server, `tp ${player.name} ${origin.x + 0.5} ${origin.y} ${origin.z + 2.5} 0 0`);
    await tick.sprint(server, 2);
    await frames(client, 10);
    await input.key(client, { keyCode: 86 });
    await screen.waitFor(client, "OverheadScreen", { timeoutMs: 8_000 });
    const combat = (await screen.widgets(client)).find(widget => /^RTS$/.test(widget.label?.trim() ?? ""));
    expect(combat, "RTS must be present in the combat profile").toBeTruthy();
    await screen.clickWidget(client, { label: combat!.label });
    await waitFor("combat roster", client, server, context, value => value.client.combat && value.client.roster >= 3);

    const empty = await client.run(READ_CLIENT);
    expectMode(empty, false);
    await input.key(client, { keyCode: 71 });
    const emptyAttack = await client.run(READ_CLIENT);
    expectMode(emptyAttack, true);
    expect(emptyAttack.failure).toBe("");
    expect(emptyAttack.route.request).toBe(empty.route.request);
    expect(emptyAttack.control.request).toBe(empty.control.request);
    await input.key(client, { keyCode: 71 });
    expectMode(await client.run(READ_CLIENT), false);
    await input.key(client, { keyCode: 72 });
    const noSelection = await client.run(READ_CLIENT);
    expect(noSelection.failureKey).toBe("folkways.rts.select_first");
    expect(noSelection.failure).not.toBe("");
    expect(noSelection.failureRemaining).toBeGreaterThan(0);
    expect(noSelection.failureRemaining).toBeLessThanOrEqual(3000);
    evidence.noSelectionFailure = noSelection;
    await failureExpired(client);

    const boxes = await Promise.all(residents.map(entity => project(client, origin, entity)));
    const from = { x: Math.min(...boxes.map(box => box.left)) - 3, y: Math.min(...boxes.map(box => box.top)) - 3 };
    const to = { x: Math.max(...boxes.map(box => box.right)) + 3, y: Math.max(...boxes.map(box => box.bottom)) + 3 };
    visible({ ...boxes[0], x: from.x, y: from.y });
    visible({ ...boxes[0], x: to.x, y: to.y });
    evidence.boxGesture = { from, to, boxes };
    const beforeBox = (await client.run(READ_CLIENT)).control.request;
    await input.drag(client, { from, to, steps: 8, button: "left" });
    evidence.boxSelection = await selection(client, server, context, residents, beforeBox);

    const third = residents[2];
    const shiftClickThird = async () => {
      const at = await project(client, origin, third);
      visible(at);
      await input.click(client, at, { button: "left", modifiers: ["shift"] });
    };
    let before = (await client.run(READ_CLIENT)).control.request;
    await shiftClickThird();
    evidence.shiftRemove = await selection(client, server, context, residents.slice(0, 2), before);
    before = (await client.run(READ_CLIENT)).control.request;
    await shiftClickThird();
    evidence.shiftAdd = await selection(client, server, context, residents, before);

    const bindOne = async (members: string[]) => {
      const request = (await client.run(READ_CLIENT)).control.request;
      await input.key(client, { keyCode: 49, modifiers: ["ctrl"] });
      return waitFor("Ctrl+1 authoritative exact binding", client, server, context, value =>
        value.client.control.request > request && value.client.control.accepted && !value.client.controlPending
        && same(value.client.control.groups[0] ?? [], members) && same(value.server.groups[0] ?? [], members)
        && same(value.client.selected, value.server.selection));
    };
    evidence.bindABC = await bindOne(residents);
    before = (await client.run(READ_CLIENT)).control.request;
    await shiftClickThird();
    await selection(client, server, context, residents.slice(0, 2), before);
    const ab = await bindOne(residents.slice(0, 2));
    expect(ab.server.owned[2], "C loses its final reference after exact ABC -> AB replacement").toBe(false);
    expect(ab.server.reserved[2]).toBe(false);
    evidence.exactAB = ab;
    before = (await client.run(READ_CLIENT)).control.request;
    await shiftClickThird();
    await selection(client, server, context, residents, before);
    evidence.rebindABC = await bindOne(residents);
    const unbound = await bindOne([]);
    expect(same(unbound.client.selected, residents), "same-set Ctrl+1 keeps visible selection").toBe(true);
    expect(same(unbound.server.selection, residents)).toBe(true);
    expect(unbound.server.owned).toEqual([true, true, true]);
    evidence.sameSetUnbind = unbound;

    const groupTextures = async (slots: number[]) => {
      await frames(client, 3);
      const groups = (await client.run(READ_CLIENT)).buttons.filter(hit => hit.kind === "GROUP");
      expect(groups).toHaveLength(9);
      expect(groups.filter(hit => hit.texture === "pressed").map(hit => hit.slot)).toEqual(slots);
      expect(groups.filter(hit => !slots.includes(hit.slot)).every(hit => hit.texture !== "pressed")).toBe(true);
    };
    const bind = async (slot: number, members: string[]) => {
      const request = (await client.run(READ_CLIENT)).control.request;
      await input.key(client, { keyCode: 48 + slot, modifiers: ["ctrl"] });
      return waitFor(`Ctrl+${slot} binding ACK`, client, server, context, value =>
        value.client.control.request > request && value.client.control.accepted && !value.client.controlPending
          && same(value.client.control.groups[slot - 1] ?? [], members)
          && same(value.server.groups[slot - 1] ?? [], members));
    };
    await bindOne(residents);
    await groupTextures([1]);
    before = (await client.run(READ_CLIENT)).control.request;
    await shiftClickThird();
    await selection(client, server, context, residents.slice(0, 2), before);
    await groupTextures([]);
    await bind(2, residents.slice(0, 2));
    await groupTextures([2]);
    before = (await client.run(READ_CLIENT)).control.request;
    await input.key(client, { keyCode: 49 });
    await selection(client, server, context, residents, before);
    await groupTextures([1]);
    const secondSlot = (await client.run(READ_CLIENT)).buttons.find(hit => hit.kind === "GROUP" && hit.slot === 2)!;
    before = (await client.run(READ_CLIENT)).control.request;
    await input.click(client, {
      x: secondSlot.x + secondSlot.width / 2, y: secondSlot.y + secondSlot.height / 2,
    }, { button: "left" });
    await selection(client, server, context, residents.slice(0, 2), before);
    await groupTextures([2]);
    before = (await client.run(READ_CLIENT)).control.request;
    await input.key(client, { keyCode: 49 });
    await selection(client, server, context, residents, before);
    await bind(3, residents);
    await groupTextures([1, 3]);
    await bind(3, []);
    await groupTextures([1]);
    await bind(2, residents);
    await groupTextures([1, 2]);
    await bind(2, []);
    await bindOne([]);
    await groupTextures([]);
    evidence.pressedGroupSwitching = await client.run(READ_CLIENT);

    const a = { x: origin.x - 5, y: origin.y, z: origin.z + 11 };
    const b = { x: origin.x, y: origin.y, z: origin.z + 14 };
    const c = { x: origin.x + 6, y: origin.y, z: origin.z + 11 };
    const groundClick = async (point: Vec, control = false) => {
      const at = await project(client, point);
      visible(at);
      await input.click(client, at, { button: "right", modifiers: control ? ["ctrl"] : [] });
    };
    let routeRequest = (await client.run(READ_CLIENT)).route.request;
    await groundClick(a);
    const moving = await routeAck(client, server, context, routeRequest, "MOVE", [a], -1, false);
    expect(moving.server.kind).toBe("DIRECT");
    expect(moving.server.complete).toBe(false);
    const squad = moving.server.group;
    const generation = moving.server.command;
    expect(squad).not.toBe("");
    expect(moving.server.memberGroups).toEqual([squad, squad, squad]);
    expect(same(moving.server.members, residents)).toBe(true);
    evidence.ordinaryMove = moving;
    for (const intent of ["MOVE", "ATTACK", "WAYPOINT", "STOP", "CONTROL"]) {
      const beforeRejection = await client.run(READ_CLIENT);
      const request = beforeRejection.route.request;
      expect(await client.run(REJECTED_PACKET, { ...origin, intent })).toBe(true);
      let rejected: Observation;
      if (["MOVE", "ATTACK", "WAYPOINT"].includes(intent)) {
        rejected = await waitFor(`${intent} rejection retains the accepted route and reports only failure`,
          client, server, context, value => value.client.route.request > request
            && !value.client.routePending && !value.client.route.accepted && value.client.route.intent === intent);
      } else {
        // Stale requests are deliberately ignored by the client's ACK cache; the server still rejects them.
        await tick.sprint(server, 4);
        await frames(client, 12);
        rejected = { client: await client.run(READ_CLIENT), server: await server.run(READ_SERVER, context) };
      }
      expect(rejected.client.overlay, `${intent} must use the bottom failure row, not action-bar text`).toBe("");
      if (["MOVE", "ATTACK", "WAYPOINT"].includes(intent)) {
        expect(rejected.client.failureKey).toBe("folkways.combat.error.rejected");
        expect(rejected.client.failure).not.toBe("");
        expect(rejected.client.failureRemaining).toBeGreaterThan(0);
        expect(rejected.client.failureRemaining).toBeLessThanOrEqual(3000);
      } else {
        expect(rejected.client.failureUntil, "stale replies must not create or extend a failure").toBe(beforeRejection.failureUntil);
      }
      expect(rejected.server.command).toBe(generation);
      expect(samePoints(rejected.server.points, [a])).toBe(true);
      expect(same(rejected.client.selected, residents)).toBe(true);
      evidence[`rejected${intent}`] = rejected;
    }
    await failureExpired(client);
    await shot(client, testInfo, run, "ordinary-route-moving");

    const completed = await waitFor("ordinary point completes its physical assembly and 20-tick dwell", client, server, context,
      value => value.server.command === generation && value.server.complete
        && value.client.state.command === generation && value.client.state.complete,
      260, 10);
    expect(completed.server.phase).toBe("HOLDING");
    expect(completed.server.waiting).toBe(0);
    expect(completed.server.owned).toEqual([true, true, true]);
    expect(completed.client.cachedPoints, "completed authored history stays available for Ctrl append/review").toBe(1);
    evidence.ordinaryComplete = completed;
    await shot(client, testInfo, run, "ordinary-route-completed-hidden");
    await input.key(client, { keyCode: 341, action: "press" });
    try {
      await frames(client, 2);
      await shot(client, testInfo, run, "completed-ctrl-history");
    } finally {
      await input.key(client, { keyCode: 341, action: "release" });
    }

    routeRequest = (await client.run(READ_CLIENT)).route.request;
    await groundClick(b, true);
    evidence.appendB = await routeAck(client, server, context, routeRequest, "WAYPOINT", [a, b], -1, false);
    routeRequest = (await client.run(READ_CLIENT)).route.request;
    await groundClick(c, true);
    evidence.appendC = await routeAck(client, server, context, routeRequest, "WAYPOINT", [a, b, c], -1, false);
    routeRequest = (await client.run(READ_CLIENT)).route.request;
    await groundClick(b, true);
    const loop = await routeAck(client, server, context, routeRequest, "WAYPOINT", [a, b, c], 1, false);
    expect(loop.server.group).toBe(squad);
    expect(loop.server.command, "append/closure keeps the accepted generation").toBe(generation);
    evidence.closedSuffix = loop;
    await shot(client, testInfo, run, "committed-loop");

    const keys = await client.run(KEY_SETTINGS, { stop: 72 });
    expect(keys.codes).toEqual([71, 72, 74]);
    expect(keys.categories).toEqual(Array(3).fill("key.categories.folkways.rts"));
    expect(keys.hintBindings).toContain("key.folkways.rts.attack_move");
    evidence.keySettings = keys;
    expect((await client.run(READ_CLIENT)).buttons.some(hit =>
      ["ATTACK_MOVE", "STOP", "RELEASE"].includes(hit.kind))).toBe(false);
    routeRequest = (await client.run(READ_CLIENT)).route.request;
    await input.key(client, { keyCode: 72 });
    const stopped = await waitFor("H stop accepted, retaining the actual squad", client, server, context, value =>
      value.client.route.request > routeRequest && value.client.route.accepted && value.client.route.intent === "STOP"
      && !value.client.routePending && value.server.kind === "STOP" && value.server.points.length === 0);
    expect(stopped.server.group).toBe(squad);
    expect(stopped.server.command).not.toBe(generation);
    expect(stopped.server.owned).toEqual([true, true, true]);
    expect(same(stopped.client.selected, residents)).toBe(true);
    expect(stopped.client.overlay).toBe("");
    expect(stopped.client.failure).toBe("");
    evidence.stopped = stopped;

    await input.key(client, { keyCode: 71, action: "press" });
    await input.key(client, { keyCode: 71, action: "repeat" });
    expect((await client.run(READ_CLIENT)).attackMode, "holding G toggles once without repeat toggling").toBe(true);
    await input.key(client, { keyCode: 71, action: "release" });
    await input.key(client, { keyCode: 71, modifiers: ["shift"] });
    expect((await client.run(READ_CLIENT)).attackMode, "Shift keeps its selection modifier ownership").toBe(true);
    expectMode(await client.run(READ_CLIENT), true);
    await bindOne(residents);
    before = (await client.run(READ_CLIENT)).control.request;
    const selectedPixel = await project(client, origin, third);
    await input.click(client, selectedPixel, { button: "left" });
    const leftSelection = await selection(client, server, context, [third], before);
    expectMode(leftSelection.client, true);
    expect(leftSelection.server.command).toBe(stopped.server.command);
    before = leftSelection.client.control.request;
    await input.key(client, { keyCode: 49 });
    const recalled = await selection(client, server, context, residents, before);
    expectMode(recalled.client, true);
    before = recalled.client.control.request;
    await input.key(client, { keyCode: 256 });
    const deselected = await selection(client, server, context, [], before);
    expectMode(deselected.client, true);
    before = deselected.client.control.request;
    await input.key(client, { keyCode: 49 });
    const restored = await selection(client, server, context, residents, before);
    expectMode(restored.client, true);
    routeRequest = restored.client.route.request;
    const attackPoint = { x: origin.x + 6, y: origin.y, z: origin.z + 7 };
    const attackPixel = await project(client, attackPoint);
    visible(attackPixel);
    await mustCommand(server, `setblock ${attackPoint.x} ${attackPoint.y} ${attackPoint.z} minecraft:water`);
    await tick.sprint(server, 2);
    await frames(client, 12);
    await input.click(client, attackPixel, { button: "right" });
    const invalid = await client.run(READ_CLIENT);
    expect(invalid.attackMode, "invalid ground preserves the persistent movement mode").toBe(true);
    expect(invalid.route.request).toBe(routeRequest);
    expect(same(invalid.selected, residents), "invalid RMB keeps the selection").toBe(true);
    expect(invalid.failureKey).toBe("folkways.rts.ground_required");
    expect(invalid.failure).not.toBe("");
    expect(await client.run(REJECTED_PACKET, { ...origin, intent: "ATTACK" })).toBe(true);
    const replacedFailure = await waitFor("a new server failure replaces the local ground failure", client, server, context,
      value => value.client.route.request > routeRequest && !value.client.route.accepted
        && value.client.failureKey === "folkways.combat.error.rejected");
    expect(replacedFailure.client.failureUntil).toBeGreaterThan(invalid.failureUntil);
    expect(replacedFailure.client.attackMode).toBe(true);
    evidence.replacedFailure = replacedFailure;
    routeRequest = replacedFailure.client.route.request;
    await client.run(RESIZE, { width: 1920, height: 1080, scale: 2 });
    await frames(client, 4);
    expect((await client.run(READ_CLIENT)).failureKey).toBe("folkways.combat.error.rejected");
    await shot(client, testInfo, run, "failure-only-1920x1080");
    await client.run(RESIZE, { width: 1280, height: 720, scale: 1 });
    await failureExpired(client);
    await mustCommand(server,
      `fill ${attackPoint.x - 3} ${attackPoint.y} ${attackPoint.z - 3} ${attackPoint.x + 3} ${attackPoint.y + 1} ${attackPoint.z + 3} minecraft:air`);
    await tick.sprint(server, 2);
    await frames(client, 12);
    const dryAttackPixel = await project(client, attackPoint);
    visible(dryAttackPixel);
    await input.click(client, dryAttackPixel, { button: "right" });
    const attackMove = await routeAck(client, server, context, routeRequest, "MOVE", [attackPoint], -1, true);
    expect(attackMove.server.kind).toBe("ATTACK_MOVE");
    const attackSquad = attackMove.server.group;
    expect(attackSquad).not.toBe("");
    expect(same(attackMove.server.members, residents)).toBe(true);
    expect(attackMove.server.memberGroups).toEqual([attackSquad, attackSquad, attackSquad]);
    expect(attackMove.server.command).not.toBe(stopped.server.command);
    expect(attackMove.server.owned).toEqual([true, true, true]);
    expect(attackMove.server.reserved).toEqual([true, true, true]);
    if (attackSquad !== squad) {
      expect(await server.run(GROUP_EXISTS, { colony, group: squad }), "a new ground route removes the superseded empty squad").toBe(false);
    }
    expectMode(attackMove.client, true);
    expect(same(attackMove.client.selected, residents)).toBe(true);
    evidence.attackMoveNoHostileFixture = attackMove;
    routeRequest = attackMove.client.route.request;
    await groundClick(b, true);
    const attackAppend = await routeAck(client, server, context, routeRequest, "WAYPOINT", [attackPoint, b], -1, true);
    expectMode(attackAppend.client, true);
    expect(attackAppend.server.command).toBe(attackMove.server.command);
    evidence.attackMoveQueue = attackAppend;
    await input.key(client, { keyCode: 71 });
    expectMode(await client.run(READ_CLIENT), false);
    routeRequest = (await client.run(READ_CLIENT)).route.request;
    await groundClick(c, true);
    const mismatch = await client.run(READ_CLIENT);
    expect(mismatch.failureKey).toBe("folkways.rts.route_mode_mismatch");
    expect(mismatch.route.request).toBe(routeRequest);
    expect(mismatch.cachedCommand).toBe(attackMove.server.command);
    await failureExpired(client);
    await groundClick(a);
    const moveAgain = await routeAck(client, server, context, routeRequest, "MOVE", [a], -1, false);
    expectMode(moveAgain.client, false);
    evidence.backToMove = moveAgain;
    await input.key(client, { keyCode: 71 });
    await groundClick(c, true);
    expect((await client.run(READ_CLIENT)).failureKey).toBe("folkways.rts.route_mode_mismatch");
    expect((await client.run(READ_CLIENT)).route.request).toBe(moveAgain.client.route.request);
    await failureExpired(client);
    routeRequest = moveAgain.client.route.request;
    await groundClick(attackPoint);
    // A new preference must survive the previous mode's acknowledgement without mutating that order.
    await input.key(client, { keyCode: 71 });
    const inFlight = await routeAck(client, server, context, routeRequest, "MOVE", [attackPoint], -1, true);
    expectMode(inFlight.client, false);
    evidence.preferenceAcrossAcknowledgement = inFlight;
    await input.key(client, { keyCode: 71 });
    for (const size of [{ width: 1920, height: 1080 }, { width: 854, height: 480 }]) {
      await client.run(RESIZE, { ...size, scale: 2 });
      await frames(client, 12);
      const rendered = await client.run(READ_CLIENT);
      expect(rendered.barHeight).toBe(60);
      const cards = rendered.buttons;
      expect(cards.filter(hit => hit.kind === "GROUP")).toHaveLength(9);
      expect(cards.filter(hit => hit.kind === "UNIT")).toHaveLength(3);
      const groups = cards.filter(hit => hit.kind === "GROUP");
      const units = cards.filter(hit => hit.kind === "UNIT");
      expect(groups[0].x).toBe(14);
      const groupRight = Math.max(...groups.map(hit => hit.x + hit.width));
      const portraitsLeft = Math.min(...cards.filter(hit => hit.kind !== "GROUP").map(hit => hit.x));
      expect(portraitsLeft - groupRight).toBeGreaterThanOrEqual(8);
      expect(groups[0].y).toBeGreaterThanOrEqual(units[0].y);
      expect(groups[0].y + groups[0].height).toBeLessThanOrEqual(units[0].y + units[0].height);
      expect(groups.filter(hit => hit.texture === "pressed").map(hit => hit.slot)).toEqual([1]);
      const unitCenter = (units[0].x + units.at(-1)!.x + units.at(-1)!.width) / 2;
      expect(Math.abs(unitCenter - size.width / 4)).toBeLessThanOrEqual(1);
      for (const hit of cards) {
        expect(hit.x).toBeGreaterThanOrEqual(14);
        expect(hit.x + hit.width).toBeLessThanOrEqual(size.width / 2 - 14);
        expect(hit.y + hit.height).toBeLessThan(size.height / 2 - 7);
        for (const other of cards.filter(other => other !== hit)) {
          expect(hit.x >= other.x + other.width || other.x >= hit.x + hit.width
            || hit.y >= other.y + other.height || other.y >= hit.y + hit.height).toBe(true);
        }
      }
      expectMode(await client.run(READ_CLIENT), true);
      await shot(client, testInfo, run, `book-bar-${size.width}x${size.height}`);
    }
    await client.run(RESIZE, { width: 640, height: 480, scale: 2 });
    await frames(client, 12);
    const narrow = (await client.run(READ_CLIENT)).buttons;
    expect(narrow.filter(hit => hit.kind === "GROUP")).toHaveLength(9);
    const narrowRight = Math.max(...narrow.filter(hit => hit.kind === "GROUP").map(hit => hit.x + hit.width));
    const narrowLeft = Math.min(...narrow.filter(hit => hit.kind !== "GROUP").map(hit => hit.x));
    expect(narrowLeft - narrowRight).toBeGreaterThanOrEqual(8);
    const oneCard = narrow.filter(hit => hit.kind === "UNIT");
    expect(oneCard).toHaveLength(1);
    expect(narrow.filter(hit => ["PREVIOUS", "NEXT"].includes(hit.kind))).toHaveLength(2);
    const next = narrow.find(hit => hit.kind === "NEXT")!;
    await input.click(client, { x: next.x + next.width / 2, y: next.y + next.height / 2 }, { button: "left" });
    await frames(client, 3);
    const advanced = (await client.run(READ_CLIENT)).buttons;
    expect(advanced.find(hit => hit.kind === "UNIT")!.resident).not.toBe(oneCard[0].resident);
    const previous = advanced.find(hit => hit.kind === "PREVIOUS")!;
    await input.click(client, { x: previous.x + previous.width / 2, y: previous.y + previous.height / 2 }, { button: "left" });
    await frames(client, 3);
    expect((await client.run(READ_CLIENT)).buttons.find(hit => hit.kind === "UNIT")!.resident).toBe(oneCard[0].resident);
    evidence.narrowPaging = { narrow, advanced };
    await client.run(RESIZE, { width: 1280, height: 720, scale: 1 });
    await frames(client, 12);
    await bindOne([]);
    await checkModeLayers(client, server, { colony, ...origin });
    const modeReturn = await waitFor("mode return keeps the accepted command generation", client, server, context,
      value => value.client.combat && !value.client.controlPending
        && value.server.group === inFlight.server.group && value.server.command === inFlight.server.command);
    expectMode(modeReturn.client, false);
    evidence.modeRoundTrip = modeReturn;
    routeRequest = (await client.run(READ_CLIENT)).route.request;
    const rebound = await client.run(KEY_SETTINGS, { stop: 75 });
    expect(rebound.codes).toEqual([71, 75, 74]);
    expect(rebound.hintBindings).toContain("key.folkways.rts.stop");
    await input.key(client, { keyCode: 72 });
    expect((await client.run(READ_CLIENT)).route.request, "old H binding must stop issuing commands").toBe(routeRequest);
    await input.key(client, { keyCode: 75 });
    const finalStop = await waitFor("second Stop replaces attack move", client, server, context, value =>
      value.client.route.request > routeRequest && value.client.route.accepted && value.client.route.intent === "STOP"
      && value.server.kind === "STOP" && value.server.points.length === 0 && !value.client.routePending);
    expect(finalStop.server.group).toBe(inFlight.server.group);
    expect(finalStop.server.command).not.toBe(inFlight.server.command);
    const stopGeneration = finalStop.server.command;

    await input.key(client, { keyCode: 258 });
    await frames(client, 4);
    expect((await client.run(READ_CLIENT)).combat, "Tab must leave the combat tool").toBe(false);
    await input.key(client, { keyCode: 71 });
    expect((await client.run(READ_CLIENT)).attackMode, "G is inactive after leaving the RTS tool").toBe(false);
    await tick.sprint(server, 2);
    let returned = false;
    for (let step = 0; step < 12; step++) {
      await input.key(client, { keyCode: 258 });
      await tick.sprint(server, 1);
      await frames(client, 4);
      if ((await client.run(READ_CLIENT)).combat) { returned = true; break; }
    }
    expect(returned, "Tab cycles the book gestures and returns to Combat").toBe(true);
    const resumed = await waitFor("Tab return restores authoritative selection without replacing orders", client, server, context,
      value => value.client.combat && !value.client.controlPending && same(value.client.selected, residents)
        && value.server.group === inFlight.server.group && value.server.command === stopGeneration);
    expect(resumed.server.owned).toEqual([true, true, true]);
    evidence.tabRoundTrip = resumed;
    await expect.poll(async () => {
      await frames(client, 10);
      return (await client.run(READ_CLIENT)).overlay;
    }, { timeout: 8_000, message: "the non-RTS book gesture hint from Tab must expire before the next order" }).toBe("");

    await bindOne(residents);
    before = (await client.run(READ_CLIENT)).control.request;
    // Leaving the tool resets the preference; repeated RMB orders keep the new mode.
    await input.key(client, { keyCode: 71 });
    routeRequest = (await client.run(READ_CLIENT)).route.request;
    await groundClick(attackPoint, true);
    const rightAttack = await routeAck(client, server, context, routeRequest, "WAYPOINT", [attackPoint], -1, true);
    expectMode(rightAttack.client, true);
    routeRequest = rightAttack.client.route.request;
    await groundClick(b);
    const repeatedAttack = await routeAck(client, server, context, routeRequest, "MOVE", [b], -1, true);
    expectMode(repeatedAttack.client, true);
    evidence.repeatedAttackMove = repeatedAttack;
    evidence.rightAttackMove = rightAttack;
    await input.key(client, { keyCode: 74 });
    const released = await waitFor("Release removes the selected residents from every control reference", client, server, context,
      value => value.client.control.request > before && value.client.control.accepted && !value.client.controlPending
        && value.client.selected.length === 0 && value.server.selection.length === 0
        && value.server.groups.every(group => group.length === 0)
        && value.server.owned.every(owned => !owned) && value.server.reserved.every(reserved => !reserved)
        && value.server.memberGroups.every(group => group === "") && value.server.orders.every(order => order === "NONE"));
    expect(released.client.overlay).toBe("");
    expect(released.client.failure).toBe("");
    expectMode(released.client, true);
    evidence.released = released;
    await shot(client, testInfo, run, "released");
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await testInfo.attach("combat-rts-input-evidence.json", { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

function expectMode(state: ClientState, attack: boolean) {
  expect(state.attackMode).toBe(attack);
  expect(state.modeLabel).toBe(attack ? "Attack move" : "Move");
  expect(state.modeHint).toContain("Move / Attack move");
  expect(state.modeColors).toEqual(attack ? [0x888888, 0xFFE29A] : [0xFFE29A, 0x888888]);
  expect(state.modeBold).toEqual([!attack, attack]);
}

function same(actual: string[], expected: string[]) {
  const a = [...actual].sort(), b = [...expected].sort();
  return a.length === b.length && a.every((id, index) => id === b[index]);
}

function samePoints(actual: Vec[], expected: Vec[]) {
  return actual.length === expected.length && actual.every((point, index) =>
    point.x === expected[index].x && point.y === expected[index].y && point.z === expected[index].z);
}

function visible(at: Pixel) {
  expect(at.x, "input must lie inside the rendered viewport").toBeGreaterThan(5);
  expect(at.x).toBeLessThan(at.width - 5);
  expect(at.y, "input must avoid the toolbar").toBeGreaterThan(40);
  expect(at.y, "world input must avoid the 60px cards").toBeLessThan(at.height - 61);
}

async function project(client: MinecraftClient, point: Vec, entity = ""): Promise<Pixel> {
  return client.run(PROJECT, { ...point, entity });
}

async function shot(client: MinecraftClient, testInfo: TestInfo, run: string, label: string) {
  const path = outAbs("e2e", "shots", `combat-rts-input-${run}-${label}.png`);
  await media.screenshot(client, { path, hideGui: false });
  await testInfo.attach(label, { path, contentType: "image/png" });
}

async function failureExpired(client: MinecraftClient) {
  await expect.poll(async () => {
    await frames(client, 6);
    return (await client.run(READ_CLIENT)).failure;
  }, { timeout: 6_000, message: "failure text must expire after about three wall-clock seconds" }).toBe("");
  const expired = await client.run(READ_CLIENT);
  expect(expired.combat).toBe(true);
  expect(expired.failureRemaining).toBe(0);
}

async function waitFor(label: string, client: MinecraftClient, server: MinecraftServer, context: Context,
  predicate: (value: Observation) => boolean, attempts = 45, ticks = 1): Promise<Observation> {
  let latest: Observation | undefined;
  for (let attempt = 0; attempt < attempts; attempt++) {
    await tick.sprint(server, ticks);
    await frames(client, 3);
    latest = { client: await client.run(READ_CLIENT), server: await server.run(READ_SERVER, context) };
    expect(latest.server.phase, `${label}: genuine group failure, ${JSON.stringify(latest.server)}`).not.toBe("FAILED_WAITING");
    if (predicate(latest)) { return latest; }
  }
  throw new Error(`${label} did not converge: ${JSON.stringify(latest)}`);
}

async function selection(client: MinecraftClient, server: MinecraftServer, context: Context, ids: string[], after: number) {
  return waitFor("selection ACK and exclusive ownership", client, server, context, value =>
    value.client.control.request > after && value.client.control.accepted && !value.client.controlPending
      && same(value.client.selected, ids) && same(value.client.control.selection, ids) && same(value.server.selection, ids));
}

async function routeAck(client: MinecraftClient, server: MinecraftServer, context: Context, after: number,
  intent: string, points: Vec[], cycleStart: number, attackMove: boolean) {
  const accepted = await waitFor(`${intent} accepted with exact authored points`, client, server, context, value =>
    value.client.route.request > after && value.client.route.accepted && value.client.route.intent === intent
      && !value.client.routePending && !value.client.replacementPending
      && samePoints(value.client.route.points, points) && samePoints(value.server.points, points)
      && value.server.cycleStart === cycleStart && value.client.route.cycleStart === cycleStart
      && value.server.attackMove === attackMove && value.server.command === value.client.route.command);
  expect(accepted.client.overlay, `${intent} acceptance must not emit action-bar feedback`).toBe("");
  expect(accepted.client.failure, `${intent} acceptance must not create failure feedback`).toBe("");
  return accepted;
}

const PREPARE_RESIDENTS = defineTask<{ colony: string } & Vec, string[]>({
  name: "folkways.combat-rts-input.prepare-flat-fixture", side: "server",
  source: javaTask({
    imports: ["java.util.UUID", "net.minecraft.server.MinecraftServer", "net.minecraft.world.entity.Mob",
      "io.github.izakyl.folkways.core.api.colony.Colonies"],
    body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();
var colony=Colonies.of(server,UUID.fromString(a.string("colony"))).orElseThrow();
var ids=colony.residents().stream().map(r->r.id()).limit(3).toList();
for(int i=0;i<ids.size();i++){
  Mob mob=null;for(var level:server.getAllLevels()){var entity=level.getEntity(ids.get(i));if(entity instanceof Mob found){mob=found;break;}}
  if(mob==null)throw new IllegalStateException("missing fixture resident "+ids.get(i));
  mob.getNavigation().stop();mob.moveTo(a.integer("x")-4+i*4+0.5,a.integer("y"),a.integer("z")+6.5,0,0);
  mob.setHealth(mob.getMaxHealth());
}
return ids.stream().map(Object::toString).toList();`,
  }),
});

const GROUP_EXISTS = defineTask<{ colony: string; group: string }, boolean>({
  name: "folkways.combat-rts-input.observe-superseded-squad", side: "server",
  source: javaTask({
    imports: ["java.util.UUID", "net.minecraft.server.MinecraftServer", "io.github.izakyl.folkways.core.api.colony.Colonies",
      "io.github.izakyl.folkways.rts.combat.group.UnitGroups"],
    body: `var a=Args.of(ctx);var colony=Colonies.of((MinecraftServer)ctx.server(),UUID.fromString(a.string("colony"))).orElseThrow();
return UnitGroups.of(colony).group(UUID.fromString(a.string("group"))).isPresent();`,
  }),
});

const READ_SERVER = defineTask<Context, ServerState>({
  name: "folkways.combat-rts-input.observe-server", side: "server",
  source: javaTask({
    imports: ["java.util.UUID", "java.util.List", "java.util.Map", "java.util.LinkedHashMap", "net.minecraft.server.MinecraftServer",
      "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.rts.combat.group.UnitGroups", "io.github.izakyl.folkways.rts.combat.command.ResidentCommands",
      "io.github.izakyl.folkways.rts.combat.control.CombatSelectionOwnership"],
    body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();var player=server.getPlayerList().getPlayerByName(a.string("player"));
var colony=Colonies.of(server,UUID.fromString(a.string("colony"))).orElseThrow();
var ids=a.strings("residents").stream().map(UUID::fromString).toList();var ownership=CombatSelectionOwnership.of(colony);
var snapshot=ownership.snapshot(player);var groups=UnitGroups.of(colony);var commands=ResidentCommands.of(colony);
var group=ids.stream().map(groups::ofResident).flatMap(java.util.Optional::stream).findFirst().orElse(null);
var out=new LinkedHashMap<String,Object>();
out.put("selection",snapshot.selection().stream().map(Object::toString).toList());
out.put("groups",snapshot.groups().stream().map(g->g.stream().map(Object::toString).toList()).toList());
out.put("owned",ids.stream().map(id->{var entity=player.serverLevel().getEntity(id);return entity!=null&&Bodies.of(entity).filter(body->ownership.owns(player,body)).isPresent();}).toList());
out.put("reserved",ids.stream().map(ownership::reserved).toList());
out.put("memberGroups",ids.stream().map(id->groups.ofResident(id).map(g->g.id().toString()).orElse("")).toList());
out.put("orders",ids.stream().map(id->commands.current(id).map(o->o.mode().name()+":"+o.status().name()).orElse("NONE")).toList());
out.put("group",group==null?"":group.id().toString());out.put("command",group==null?"":group.commandId().map(Object::toString).orElse(""));
out.put("kind",group==null?"NONE":group.commandKind().name());out.put("phase",group==null?"NONE":group.phase().name());
out.put("result",group==null?"NONE":group.endReason().name());out.put("members",group==null?List.of():group.members().stream().map(Object::toString).toList());
out.put("points",group==null?List.of():group.pointRoute().stream().map(p->Map.of("x",p.cell().getX(),"y",p.cell().getY(),"z",p.cell().getZ())).toList());
out.put("cycleStart",group==null?-1:group.cycleStart());out.put("complete",group!=null&&group.routeComplete());out.put("attackMove",group!=null&&group.attackMove());
out.put("stop",group==null?-1:group.patrolStop());out.put("waiting",group==null?0:group.waitingMembers());return out;`,
  }),
});

const READ_CLIENT = defineTask<void, ClientState>({
  name: "folkways.combat-rts-input.observe-client-and-rendered-hits", side: "client",
  source: javaTask({
    imports: ["java.util.List", "java.util.Map", "java.util.Set", "java.util.LinkedHashMap", "java.util.ArrayList",
      "io.github.izakyl.folkways.front.api.ui.OverheadTools", "io.github.izakyl.folkways.rts.combat.net.CombatPackets"],
    body: `
var out=new LinkedHashMap<String,Object>();var control=CombatPackets.controls().orElse(null);var route=CombatPackets.route().orElse(null);var state=CombatPackets.state();
var gui = net.minecraft.client.Minecraft.getInstance().gui;
var overlay = net.minecraft.client.gui.Gui.class.getDeclaredField("overlayMessageString");
overlay.setAccessible(true);
var overlayTime = net.minecraft.client.gui.Gui.class.getDeclaredField("overlayMessageTime");
overlayTime.setAccessible(true);
var message = (net.minecraft.network.chat.Component) overlay.get(gui);
out.put("overlay", overlayTime.getInt(gui) > 0 && message != null ? message.getString() : "");
out.put("roster",CombatPackets.roster().size());out.put("controlPending",CombatPackets.controlPending());out.put("routePending",CombatPackets.routePending());
out.put("control",control==null?Map.of("request",0L,"accepted",false,"operation","","selection",List.of(),"groups",List.of()):
  Map.of("request",control.request(),"accepted",control.accepted(),"operation",control.operation().name(),
    "selection",control.selection().stream().map(Object::toString).toList(),"groups",control.groups().stream().map(g->g.stream().map(Object::toString).toList()).toList()));
out.put("route",route==null?Map.of("request",0L,"accepted",false,"intent","","points",List.of(),"cycleStart",-1,"command",""):
  Map.of("request",route.request(),"accepted",route.accepted(),"intent",route.intent().name(),"cycleStart",route.cycleStart(),
    "points",route.points().stream().map(p->Map.of("x",p.cell().getX(),"y",p.cell().getY(),"z",p.cell().getZ())).toList(),"command",String.valueOf(route.state().commandId())));
out.put("state",Map.of("command",String.valueOf(state.commandId()),"complete",state.routeComplete(),"stop",state.stop(),"attackMove",state.attackMove()));
var handler=OverheadTools.selectedHandler().orElse(null);boolean combat=handler!=null&&handler.getClass().getName().endsWith(".CombatController");out.put("combat",combat);
out.put("modeLabel", "");out.put("modeHint", "");out.put("modeColors", List.of());out.put("modeBold", List.of());
out.put("failure", "");out.put("failureKey", "");out.put("failureUntil", 0L);out.put("failureRemaining", 0L);
out.put("barHeight", combat ? handler.bottomInset() : 0);
out.put("selected",List.of());out.put("cachedPoints",0);out.put("cachedCommand","");out.put("attackMode",false);out.put("replacementPending",false);out.put("buttons",List.of());
if(combat){
  var type=handler.getClass();
  var visibleFailure = type.getDeclaredMethod("visibleFailure");
  visibleFailure.setAccessible(true);
  var failure = (net.minecraft.network.chat.Component) visibleFailure.invoke(handler);
  var deadline = type.getDeclaredField("failureUntil");
  deadline.setAccessible(true);
  long until = deadline.getLong(handler);
  out.put("failure", failure == null ? "" : failure.getString());
  out.put("failureKey", failure != null && failure.getContents() instanceof
    net.minecraft.network.chat.contents.TranslatableContents translated ? translated.getKey() : "");
  out.put("failureUntil", until);
  out.put("failureRemaining", Math.max(0, until - net.minecraft.Util.getMillis()));
  var selected=type.getDeclaredField("selected");selected.setAccessible(true);out.put("selected",((Set<?>)selected.get(handler)).stream().map(Object::toString).toList());
  var points=type.getDeclaredField("route");points.setAccessible(true);out.put("cachedPoints",((List<?>)points.get(handler)).size());
  var command=type.getDeclaredField("routeCommandId");command.setAccessible(true);out.put("cachedCommand",String.valueOf(command.get(handler)));
  var attackMode=type.getDeclaredField("attackMoveMode");attackMode.setAccessible(true);out.put("attackMode",attackMode.getBoolean(handler));
  var mode = type.getDeclaredMethod("movementMode");mode.setAccessible(true);
  out.put("modeLabel", ((net.minecraft.network.chat.Component) mode.invoke(handler)).getString());
  var hint = handler.cues().get(1).does();out.put("modeHint", hint.getString());
  var options = ((net.minecraft.network.chat.contents.TranslatableContents) hint.getContents()).getArgs();
  out.put("modeColors", java.util.Arrays.stream(options).map(option -> ((net.minecraft.network.chat.Component) option).getStyle().getColor().getValue()).toList());
  out.put("modeBold", java.util.Arrays.stream(options).map(option -> ((net.minecraft.network.chat.Component) option).getStyle().isBold()).toList());
  var pending=type.getDeclaredField("pendingReplacement");pending.setAccessible(true);out.put("replacementPending",pending.get(handler)!=null);
  var cardsField=type.getDeclaredField("cards");cardsField.setAccessible(true);var cards=cardsField.get(handler);
  var hitsField=cards.getClass().getDeclaredField("hits");hitsField.setAccessible(true);var buttons=new ArrayList<Map<String,Object>>();
  var pressed = cards.getClass().getDeclaredField("PRESSED");pressed.setAccessible(true);
  var normal = cards.getClass().getDeclaredField("BUTTON");normal.setAccessible(true);
  for(var hit:(List<?>)hitsField.get(cards)){
    var actionMethod=hit.getClass().getDeclaredMethod("action");actionMethod.setAccessible(true);var action=actionMethod.invoke(hit);
    var kindMethod=action.getClass().getDeclaredMethod("kind");kindMethod.setAccessible(true);var row=new LinkedHashMap<String,Object>();row.put("kind",kindMethod.invoke(action).toString());
    var slot = action.getClass().getDeclaredMethod("slot");slot.setAccessible(true);row.put("slot",slot.invoke(action));
    var resident = action.getClass().getDeclaredMethod("resident");resident.setAccessible(true);
    row.put("resident", String.valueOf(resident.invoke(action)));
    var texture = hit.getClass().getDeclaredMethod("texture");texture.setAccessible(true);
    var drawn = texture.invoke(hit);row.put("texture",drawn == pressed.get(null) ? "pressed" : drawn == normal.get(null) ? "normal" : "hover");
    for(var field:List.of("x","y","width","height")){var accessor=hit.getClass().getDeclaredMethod(field);accessor.setAccessible(true);row.put(field,accessor.invoke(hit));}buttons.add(row);
  }out.put("buttons",buttons);
}return out;`,
  }),
});

const PROJECT = defineTask<Vec & { entity: string }, Pixel>({
  name: "folkways.combat-rts-input.observe-world-projection", side: "client",
  source: javaTask({
    imports: ["java.util.Map", "java.util.Optional", "java.util.UUID", "net.minecraft.client.Minecraft", "net.minecraft.world.phys.Vec3", "net.minecraft.world.phys.AABB"],
    body: `
var a=Args.of(ctx);var mc=Minecraft.getInstance();var type=Class.forName("io.github.izakyl.folkways.rts.client.CombatProjection");
var constructor=type.getDeclaredConstructor(Minecraft.class);constructor.setAccessible(true);var projection=constructor.newInstance(mc);
double left,top,right,bottom;
if(!a.string("entity").isEmpty()){
  var id=UUID.fromString(a.string("entity"));net.minecraft.world.entity.Entity found=null;
  for(var entity:mc.level.entitiesForRendering()){if(entity.getUUID().equals(id)){found=entity;break;}}
  if(found==null)throw new IllegalStateException("fixture resident is not client-tracked: "+id);
  var bounds=type.getDeclaredMethod("bounds",AABB.class);bounds.setAccessible(true);var rect=((Optional<?>)bounds.invoke(projection,found.getBoundingBox())).orElseThrow();
  var l=rect.getClass().getDeclaredMethod("left");l.setAccessible(true);left=((Number)l.invoke(rect)).doubleValue();
  var t=rect.getClass().getDeclaredMethod("top");t.setAccessible(true);top=((Number)t.invoke(rect)).doubleValue();
  var r=rect.getClass().getDeclaredMethod("right");r.setAccessible(true);right=((Number)r.invoke(rect)).doubleValue();
  var b=rect.getClass().getDeclaredMethod("bottom");b.setAccessible(true);bottom=((Number)b.invoke(rect)).doubleValue();
}else{
  var point=type.getDeclaredMethod("point",Vec3.class);point.setAccessible(true);
  var pixel=((Optional<?>)point.invoke(projection,new Vec3(a.integer("x")+0.5,a.integer("y")+0.12,a.integer("z")+0.5))).orElseThrow();
  var x=pixel.getClass().getDeclaredMethod("x");x.setAccessible(true);left=right=((Number)x.invoke(pixel)).doubleValue();
  var y=pixel.getClass().getDeclaredMethod("y");y.setAccessible(true);top=bottom=((Number)y.invoke(pixel)).doubleValue();
}
return Map.of("x",(left+right)/2,"y",(top+bottom)/2,"left",left,"top",top,"right",right,"bottom",bottom,
  "width",mc.getWindow().getGuiScaledWidth(),"height",mc.getWindow().getGuiScaledHeight());`,
  }),
});

// Settings and window setup only; all command actions above enter through real input events.
const KEY_SETTINGS = defineTask<{ stop: number }, {
  codes: number[]; categories: string[]; conflicts: string[]; hintBindings: string[];
}>({
  name: "folkways.combat-rts-input.key-settings", side: "client",
  source: javaTask({
    imports: ["java.util.List", "java.util.Map", "java.util.Arrays", "net.minecraft.client.Minecraft",
      "com.mojang.blaze3d.platform.InputConstants", "net.minecraft.client.KeyMapping",
      "io.github.izakyl.folkways.rts.client.CombatKeys", "io.github.izakyl.folkways.front.api.ui.OverheadTools"],
    body: `
CombatKeys.STOP.setKey(InputConstants.Type.KEYSYM.getOrCreate((int) Args.of(ctx).integer("stop")));
KeyMapping.resetMapping();
var keys = List.of(CombatKeys.MOVE_MODE, CombatKeys.STOP, CombatKeys.RELEASE);
var conflicts = Arrays.stream(Minecraft.getInstance().options.keyMappings)
    .filter(k -> !keys.contains(k) && keys.stream().anyMatch(r -> r.getKey().equals(k.getKey())))
    .map(k -> k.getName() + ":" + k.getKey().getName()).toList();
var hints = OverheadTools.selectedHandler().orElseThrow().cues().stream()
    .map(c -> c.binding() == null ? "" : c.binding()).toList();
return Map.of("codes", keys.stream().map(k -> k.getKey().getValue()).toList(),
    "categories", keys.stream().map(KeyMapping::getCategory).toList(), "conflicts", conflicts, "hintBindings", hints);`,
  }),
});

const RESIZE = defineTask<{ width: number; height: number; scale: number }, boolean>({
  name: "folkways.combat-rts-input.resize-for-visual-review", side: "client",
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft", "org.lwjgl.glfw.GLFW"],
    body: `var a = Args.of(ctx);
var mc = Minecraft.getInstance();
GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), (int) a.integer("width"), (int) a.integer("height"));
mc.options.guiScale().set((int) a.integer("scale"));
mc.resizeDisplay();
return true;`,
  }),
});

// Invalid ground/mob targets and stale packets exercise real server refusal paths without new UI controls.
const REJECTED_PACKET = defineTask<Vec & { intent: string }, boolean>({
  name: "folkways.combat-rts-input.probe-failure-only-rejections", side: "client",
  source: javaTask({
    imports: ["java.util.List", "java.util.UUID", "net.minecraft.client.Minecraft", "net.minecraft.core.BlockPos",
      "io.github.izakyl.folkways.core.api.terms.WorldPos", "io.github.izakyl.folkways.rts.combat.net.CombatPackets",
      "io.github.izakyl.folkways.rts.combat.net.CombatControlPacket",
      "io.github.izakyl.folkways.rts.combat.net.CombatStopPacket", "net.neoforged.neoforge.network.PacketDistributor"],
    body: `
var a = Args.of(ctx);
var mc = Minecraft.getInstance();
var current = CombatPackets.controls().orElseThrow();
var ids = current.selection();
var invalid = WorldPos.of(mc.level, new BlockPos((int) a.integer("x"), (int) a.integer("y") + 20,
    (int) a.integer("z")));
return switch (a.string("intent")) {
    case "MOVE" -> CombatPackets.submit(ids, List.of(invalid), -1, false);
    case "ATTACK" -> CombatPackets.attack(ids, UUID.randomUUID());
    case "WAYPOINT" -> CombatPackets.append(ids, invalid, false);
    case "STOP" -> {
        PacketDistributor.sendToServer(new CombatStopPacket(1, current.colonyId(), ids));
        yield true;
    }
    case "CONTROL" -> {
        PacketDistributor.sendToServer(new CombatControlPacket(1, current.colonyId(),
            CombatControlPacket.Operation.RELEASE, 0, ids));
        yield true;
    }
    default -> false;
};`,
  }),
});
