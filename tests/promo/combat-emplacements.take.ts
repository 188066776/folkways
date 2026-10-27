import { expect, test } from "@playwright/test";
import { camera } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { openSettlement, settlementCamera } from "./combat-settlement";
import { combatCast, combatState, combatWave, OBSERVE_EVENTS, STATION_DIAGNOSTIC } from "./combat-kit";
import { CLEAR_STAGING_MESSAGES } from "./combat-presentation";
import { promoClip, sleepMs, take, type PromoPair } from "./promo-kit";

// Each entity has its own enrolled holding and one pinned staffing slot.
test("combat: staffed mixed emplacement battery", async ({}, testInfo) => {
  const run = take("combat-emplacements");
  let pair: PromoPair | undefined;
  try {
    const scene = await openSettlement(run.scope, run);
    pair = scene.pair;
    await scene.server.run(OBSERVE_EVENTS, {});
    const cast = [];
    for (const [i, id] of ["mk_42", "bl_132", "mle_1934"].entries()) {
      cast.push(await combatCast({ ...scene, o: scene.at(-12 + i * 12, 0, 0) }, `superbwarfare:${id}`, true));
    }
    run.note("cast", cast);
    run.note("rules", {
      staffing:
        "Each exact station entity separately enrolled as weapon_station, with one pinned operator/profile slot",
      sight: "Default dynamic vision and real spyglasses in finite profiles; native LOS required",
      ballistics: "Native velocity, spread, pitch, trajectory and friendly/blast safety",
      ammunition: "32 physical native HE shells for each operator; no infinite ammo",
    });
    const establishing = {
      eye: scene.at(-47, 18, 50),
      target: scene.at(4, 1, 44),
      fov: 72,
    };
    const impacts = {
      eye: scene.at(-28, 12, 66),
      target: scene.at(9, 1, 45),
      fov: 78,
    };
    await settlementCamera(scene, establishing.eye, establishing.target, establishing.fov);
    await scene.client.run(CLEAR_STAGING_MESSAGES);
    const before = await combatState(scene);
    for (const member of cast) {
      expect(before.crew.find((p: any) => p.uuid === member.uuid).vehicle).toBe(member.station);
    }
    run.note("cameras", { establishing, impacts, cutAfterSeconds: 13 });
    const samples: any[] = [];
    await promoClip(run, scene.client, scene.server, "01-battery", {
      seconds: 48,
      fps: 30,
      subject: "Three independently staffed SBW emplacements defend the same settlement",
      worldState: { cast, before },
      during: async () => {
        const start = Date.now();
        await sleepMs(2500);
        run.note("wave", await combatWave({ ...scene, o: scene.at(8, 0, 0) }, 70, 500, 12, 0.14));
        let cut = false;
        while (Date.now() - start < 46_000) {
          samples.push({
            ...(await combatState(scene)),
            atMs: Date.now() - start,
          });
          if (!cut && Date.now() - start >= 13_000) {
            await camera.set(scene.client, {
              position: impacts.eye,
              ...cameraLookingAt(impacts.eye, impacts.target),
              fov: impacts.fov,
            });
            cut = true;
          }
          await sleepMs(250);
        }
      },
    });
    const after = await combatState(scene);
    run.note("samples", samples);
    run.note("diagnostics", await scene.server.run(STATION_DIAGNOSTIC, {}));
    const summary = cast.map((member) => {
      const initial = before.crew.find((p: any) => p.uuid === member.uuid);
      const final = after.crew.find((p: any) => p.uuid === member.uuid);
      const mounted = samples.some((s) =>
        s.crew.some((p: any) => p.uuid === member.uuid && p.vehicle === member.station),
      );
      return {
        ...member,
        shots: final.shots,
        hits: final.hits,
        damage: final.damage,
        kills: final.kills,
        roundsBefore: initial.rounds,
        roundsAfter: final.rounds,
        mounted,
        turretYawRange: samples.reduce(
          (range: number[], s) => {
            const station = s.stations.find((p: any) => p.uuid === member.station);
            return station ? [Math.min(range[0], station.turretYaw), Math.max(range[1], station.turretYaw)] : range;
          },
          [Infinity, -Infinity],
        ),
      };
    });
    run.note("summary", summary);
    expect(new Set(cast.map((p) => p.zone)).size).toBe(cast.length);
    for (const member of summary) {
      expect(member.mounted, `${member.gun} has its own resident operator`).toBe(true);
      expect(member.shots, `${member.gun} emits native shots`).toBeGreaterThan(0);
      expect(member.hits).toBeGreaterThan(0);
      expect(member.damage).toBeGreaterThan(0);
      expect(member.roundsAfter).toBeLessThan(member.roundsBefore);
      expect(member.turretYawRange[1] - member.turretYawRange[0]).toBeGreaterThan(3);
    }
    expect(summary.reduce((n, p) => n + p.kills, 0)).toBeGreaterThan(0);
    run.expectTaken(["01-battery"]);
    run.note("verified", true);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
