import { test } from "@playwright/test";
import { camera, tick, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { existsSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { relief, survey } from "../bench/town/raise";
import { Lie, type Relief } from "../bench/town/network";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { frames } from "../shared/harness/bw-helpers";
import { FILM_LOOK } from "../shared/capture/shaders";
import { cinematicWorld, launchPromoPair, sleepMs, take, type PromoPair } from "./promo-kit";

const SEEDS = ["folkways-combat-river-1", "folkways-combat-river-2", "folkways-combat-river-3"];

// Benchmark Relief.java/Survey.java and site.ts footprint filters, with artillery approach scoring instead of railways.
// All comparisons show untouched generated land before the settlement is placed.
for (const seed of SEEDS) {
  test(`combat site: ${seed}`, async ({}, testInfo) => {
    const cachedFile = `tests/out/promo/takes/combat-site-${seed}/relief.json`;
    const cached = existsSync(cachedFile) ? JSON.parse(readFileSync(cachedFile, "utf8")) : undefined;
    const run = take(`combat-site-${seed}`);
    const config = readFileSync("blockwright.toml", "utf8")
      .replaceAll('"folkways-combat-river-1"', JSON.stringify(seed))
      .replaceAll(/path = "([^\"]+)"/g, (_, file) => `path = "${path.resolve(file)}"`);
    const file = path.resolve(`combat-site-${seed}.generated.toml`);
    writeFileSync(file, config);
    let pair: PromoPair | undefined;
    try {
      pair = await launchPromoPair(`site-${seed}`, { config: `${file}#promo-combat`, look: { options: FILM_LOOK } });
      const { server, client } = pair;
      const player = await waitForGroundedPlayer(server);
      await world.command(server, `op ${player.name}`);
      await cinematicWorld(server);
      await world.command(server, "time set 10000");
      await world.command(server, `gamemode spectator ${player.name}`);
      const x = Math.round(player.pos.x / 32) * 32;
      const z = Math.round(player.pos.z / 32) * 32;
      const land = cached ?? await relief(server, { minX: x - 1536, maxX: x + 1536,
        minZ: z - 1536, maxZ: z + 1536 }, 16);
      const ranked = rankSites(land, x, z);
      writeFileSync(path.join(run.dir, "relief.json"), JSON.stringify(land));
      run.note("seed", seed);
      run.note("ranking", ranked.slice(0, 20));
      if (!ranked.length) {
        run.note("rejected", "No footprint and approach meet the slope, biome and dry-ground bounds");
        const eye = { x, y: player.pos.y + 90, z };
        const look = cameraLookingAt(eye, player.pos);
        await tick.freeze(server, false);
        await sleepMs(10_000);
        await camera.set(client, { position: eye, ...look, fov: 70 });
        await camera.hud(client, false);
        await run.shot(client, "rejected-spawn", { subject: "Rejected seed: spawn comparison", worldState: { seed } });
      }
      for (const [i, site] of ranked.slice(0, 2).entries()) {
        const ground = await survey(server, { minX: site.x - 56, maxX: site.x + 56,
          minZ: site.z - 56, maxZ: site.z + 56 });
        writeFileSync(path.join(run.dir, `survey-${i}.json`), JSON.stringify(ground));
        const eye = { x: site.x - 95, y: site.level + 75, z: site.z + 110 };
        const look = cameraLookingAt(eye, { x: site.x, y: site.level, z: site.z });
        await camera.reset(client);
        await world.command(server,
          `tp ${player.name} ${site.x} ${site.level + 20} ${site.z} ${look.yaw} ${look.pitch}`);
        await tick.freeze(server, false);
        await sleepMs(20_000);
        await tick.freeze(server, true);
        await camera.set(client, { position: eye, ...look, fov: 70 });
        await camera.hud(client, false);
        await frames(client, 100);
        await run.shot(client, `candidate-${i}`, { subject: "Unaltered generated settlement site", worldState: site });
      }
      run.note("verified", true);
    } catch (error) {
      pair?.failing(error);
      throw error;
    } finally {
      await pair?.teardown(testInfo);
      rmSync(file, { force: true });
    }
  });
}

function rankSites(land: Relief, centerX: number, centerZ: number) {
  const lie = new Lie(land);
  const sites: any[] = [];
  for (let x = centerX - 1152; x <= centerX + 1152; x += 32) {
    for (let z = centerZ - 1152; z <= centerZ + 1152; z += 32) {
      const core = lie.samples({ minX: x - 50, maxX: x + 50, minZ: z - 50, maxZ: z + 50 });
      if (core.some((p) => p.kind === 1 || p.kind === 2)) {
        continue;
      }
      const heights = core.map((p) => p.h).sort((a, b) => a - b);
      const level = heights[heights.length >> 1];
      const range = heights.at(-1)! - heights[0];
      const mean = heights.reduce((a, b) => a + b, 0) / heights.length;
      const spread = Math.sqrt(heights.reduce((n, h) => n + (h - mean) ** 2, 0) / heights.length);
      if (spread > 2.5 || range > 9 || level < 64 || level > 110) {
        continue;
      }
      for (const direction of [0, 1, 2, 3]) {
        const dx = [0, 1, 0, -1][direction];
        const dz = [1, 0, -1, 0][direction];
        const approach = Array.from({ length: 11 }, (_, i) => ({
          h: lie.height(x + dx * (55 + i * 12), z + dz * (55 + i * 12)),
          kind: lie.kind(x + dx * (55 + i * 12), z + dz * (55 + i * 12)),
        }));
        if (approach.some((p) => p.kind === 1 || p.h < level - 4 || p.h > level + 7)) {
          continue;
        }
        const around = lie.samples({ minX: x - 150, maxX: x + 150, minZ: z - 150, maxZ: z + 150 });
        const water = around.filter((p) => p.kind === 1).length / around.length;
        const behind = lie.height(x - dx * 300, z - dz * 300) - level;
        const score = -spread * 10 - range + 40 * Math.min(water, .18) + Math.min(behind, 65) * .35;
        sites.push({ x, z, level, range, spread, water, behind, direction, score });
      }
    }
  }
  sites.sort((a, b) => b.score - a.score);
  return sites.filter((s, i) => sites.slice(0, i).every((p) => Math.hypot(s.x - p.x, s.z - p.z) > 160));
}
