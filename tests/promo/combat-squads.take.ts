import { expect, test } from "@playwright/test";
import { openSettlement, settlementCamera } from "./combat-settlement";
import { combatCast, combatState, combatWave, OBSERVE_EVENTS } from "./combat-kit";
import { CLEAR_STAGING_MESSAGES } from "./combat-presentation";
import { promoClip, sleepMs, take, type PromoPair } from "./promo-kit";

const SQUADS = [
  {
    name: "gate",
    weapons: ["tacz:ak47", "superbwarfare:m_870", "superbwarfare:mp_5", "tacz:m4a1"],
    eye: [-14, 5, 25],
    target: [0, 1.5, 21],
    fov: 70,
    z: 0,
    positions: [-6, -2, 2, 9],
  },
  {
    name: "flank",
    weapons: ["tacz:aug", "superbwarfare:svd", "tacz:m249", "superbwarfare:m_4"],
    eye: [14, 4, 27],
    target: [0, 1.5, 22],
    fov: 65,
    z: 3,
    positions: [-5, -2, 1, 4],
  },
];

// Independent finite profiles mix both native weapon adapters in each wave.
for (const squad of SQUADS) {
  test(`combat: mixed handheld squad ${squad.name}`, async ({}, testInfo) => {
    const run = take(`combat-squad-${squad.name}`);
    let pair: PromoPair | undefined;
    try {
      const scene = await openSettlement(run.scope, run);
      pair = scene.pair;
      await scene.server.run(OBSERVE_EVENTS, {});
      const cast = [];
      for (const [i, id] of squad.weapons.entries()) {
        cast.push(await combatCast({ ...scene, o: scene.at(squad.positions[i], 0, squad.z) }, id));
      }
      const vec = (p: number[]) => scene.at(p[0], p[1], p[2]);
      await settlementCamera(scene, vec(squad.eye), vec(squad.target), squad.fov);
      await scene.client.run(CLEAR_STAGING_MESSAGES);
      const before = await combatState(scene);
      const samples: any[] = [];
      run.note("cast", cast);
      run.note(
        "rules",
        "Configured handheld sentry zones; physical spyglasses; default dynamic sight; native AI and damage",
      );
      await promoClip(run, scene.client, scene.server, "01-defence", {
        seconds: 32,
        fps: 30,
        subject: "Four residents defend the settlement with mixed TaCZ and SBW weapons",
        worldState: { cast, before },
        during: async () => {
          run.note("wave", await combatWave({ ...scene, o: scene.at(-9, 0, 0) }, 27, 100, 12));
          const start = Date.now();
          while (Date.now() - start < 30_000) {
            samples.push({
              ...(await combatState(scene)),
              atMs: Date.now() - start,
            });
            await sleepMs(250);
          }
        },
      });
      const after = await combatState(scene);
      run.note("samples", samples);
      const summary = cast.map((member) => {
        const initial = before.crew.find((p: any) => p.uuid === member.uuid);
        const final = after.crew.find((p: any) => p.uuid === member.uuid);
        return {
          ...member,
          shots: final.shots,
          hits: final.hits,
          damage: final.damage,
          kills: final.kills,
          roundsBefore: initial.rounds,
          roundsAfter: final.rounds,
        };
      });
      run.note("summary", summary);
      for (const member of summary) {
        expect(member.shots, `${member.gun} fires on camera`).toBeGreaterThan(0);
        expect(member.hits, `${member.gun} damages a real enemy`).toBeGreaterThan(0);
        expect(member.damage).toBeGreaterThan(0);
        expect(member.roundsAfter).toBeLessThan(member.roundsBefore);
      }
      expect(summary.reduce((n, p) => n + p.kills, 0)).toBeGreaterThan(0);
      run.expectTaken(["01-defence"]);
      run.note("verified", true);
    } catch (error) {
      pair?.failing(error);
      throw error;
    } finally {
      await pair?.teardown(testInfo);
    }
  });
}
