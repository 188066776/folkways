# Combat promo / 战斗宣传片

直接进入指挥，顺序固定为 RTS → gate/flank 混编手持武器 → 独立编制武器站 → 灭火 → 修复。段间及同场景的时间省略均使用硬切，保留正常速度，以游戏原声为主、低音量背景音乐为辅。字幕全部来自 `promo-combat.toml`，沿用用户确定的文字；flank 不重复手持字幕；damage 只显示灭火支持，repair 只显示损管修复。

The capture uses persistent RTS movement mode and native station mounting. This
recovery recapture follows merge `f6010c8db`; RTS, gate, flank and stations
retain their reviewed source videos; only editing review metadata changes. Damage uses only the owner-approved fire-suppression portion of the unverified
recovery take. A separate verified and approved repair take supplies restoration.
This batch edits existing footage with FFmpeg/Python; it does not launch Minecraft.

## Recording / 录制

Use Java 21 and a GPU/EGL client in this worktree:

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew build
npm run typecheck
npm run promo:take -- combat-damage.take.ts
```

`npm run promo:combat` runs all four take files, including both handheld scenes.
Use the narrower command above to retain the other four approved sources.
`promo:combat:sites` is an optional survey and is never a film input.

| Scope                 | Clip              | Subject                                                                                       |
| --------------------- | ----------------- | --------------------------------------------------------------------------------------------- |
| `combat-rts`          | `01-command.mp4`  | 框选、Ctrl＋1 编组、单选与 1 召回、右键移动、Ctrl＋右键路点、G 切换攻击移动后右键下令、H 停止 |
| `combat-squad-gate`   | `01-defence.mp4`  | TaCZ AK47 / M4A1、SBW M870 / MP5；保留用户审看版本                                            |
| `combat-squad-flank`  | `01-defence.mp4`  | TaCZ AUG / M249、SBW SVD / M4；保留用户审看版本                                               |
| `combat-emplacements` | `01-battery.mp4`  | MK42 / BL132 / MLE1934；炮阵、来敌、转向、弹道与落点同框                                      |
| `combat-damage`       | `01-recovery.mp4` | 仅使用用户批准的 75 秒内灭火片段，硬切省略空闲：灭火器近景及远距离水带；不包含修复            |
| `combat-repair`       | `01-repair.mp4`   | 原生 TNT 爆炸、坑洞、居民补地补墙及完整复原；省略取料空档，显示损管自动修复字幕               |

Each scope writes `tests/out/promo/takes/<scope>/index.json` and its MP4.
`context.verified` is written only after all runtime assertions pass. Failed
captures remain unverified. The only editing exception is the owner-approved
partial damage take: explicit visual approval and partial approval, native-speed
edit intervals ending no later than 75 seconds, and all native tool evidence
checks still passing. Repair must be verified and visually approved, with at least
ten missing blocks observed and zero baseline differences at completion.

## Input and staffing / 输入与编制

RTS uses real client input throughout. G toggles Move/Attack Move persistently;
plain RMB replaces the route and same-mode Ctrl+RMB appends waypoints. The take
first authors a Move route, toggles G, then replaces it with an Attack Move route
using RMB. It observes the changed highlight and verifies that acceptance keeps
the new mode. Before the return movement it toggles back to Move. H interrupts
actual movement while preserving selection and exclusive control.

Ctrl+1 binds the exact three-person selection; selecting one resident clears the
group's pressed state, and 1 restores all three and presses slot 1. Read-only
observers record client highlights, nine rendered group buttons, server group
membership, command generations, ownership and displacement. Failure text and
action-bar feedback must remain empty throughout recording. The independent RTS
design audit in both combat architecture documents is separate from this finite
client run and from the final encoded-film review.

Handhelds use distinct loadout profiles and pinned sentry-zone staffing slots.
Each station entity is separately enrolled as `weapon_station`, with one pinned
operator/profile slot. The current product mounts the resident at its generated
reachable position outside the hull, including before enemies arrive. The take
checks those exact seats before introducing the wave. It never forces mounting.

The battery starts with finite native HE shells and physical spyglasses. Default
dynamic vision, LOS, turret control, native trajectories, blast safety and damage
remain active. Two oblique cameras keep both guns and approaching enemies in the
same visual narrative. Native shot/damage/death observers and inventory samples
record real emissions, positive damage, kills and ammunition use. Turret yaw
ranges demonstrate actual target acquisition, independently of visual review.

## Aftermath and recovery / 受损现场与损管

The workshop is inside the same settlement. Record its complete stone-brick and
oak baseline before staging any damage. Seeded boundary rays with varied energy
produce a rough round wall breach and a shallow crater through only the top floor
course. This borrows vanilla explosion geometry, not its damage/resistance
simulation. Intact lower foundations and the open south side preserve access.
The blast center is half a block below grade, keeping the broken wall within
normal ground-level work reach. No fire is placed on the recessed crater floor.

Fire follows joined, perturbed lobes around the intact crater rim and through the
wooden workshop interior, plus a smaller foreground patch. It is not a spaced
rectangular array. Fire ticks are disabled; no command suppresses fire or restores
a baseline block during recording.

开拍前进入损管模式，区域范围、缺损标记和悬停信息卡保持可见；不录拿书、切模式或划区域。
现有客户端没有单独隐藏／折叠左侧按键提示的设置；保留提示，将主体置于其右侧，避免使用会同时隐藏
必要 HUD 的 F1。两名持有损管许可的居民携带有限材料参与灭火与修复。

The first responder carries a physical 1000 mB water extinguisher with native tool
management enabled. After 30 mB of paid use, staffing transfers to the hose
responder and the one physical cone nozzle moves from the staged barrel into the
supplied cabinet. Normal AI must collect it and connect the hose. The cabinet is placed 28 blocks south of the incident origin, about 30 blocks
from the main fire. An open paved forecourt keeps its native spray ray clear.
Its native capacity is 1000 mB; no water is replenished during the take. Exclusive tool
phases keep per-tool attribution unambiguous. The recovery script intended to enable repair after all fires were out; this
take stalled with six fires and never entered repair. The film uses its suppression
portion only. The separate repair take records native TNT damage to an intact
stone-brick floor/wall, followed by a resident collecting finite materials from
the store and restoring all 22 missing blocks.
Only initial positions are staged; work travel and arrival remain native.
Conservative stance planning uses native range minus the ordinary 0.9-block
arrival tolerance and 0.05 margin. The old precise-arrival framework is removed.
The extinguisher retains native 4-block range; the cone hose uses native effective
50-block range (flat is 35). Server suppression samples a straight line; native
client droplets follow an arc, so their landing point is not the server effect ray.

A close camera covers the extinguisher, a wide camera includes the distant cabinet,
its connected operator, the full water arc and the remote burning workshop, and a
higher repair camera exposes both sides of the damaged wall. All cameras retain
the native Damage Control screen, area bounds, mode bar and hovered card.
Paid cabinet pulses record the resident eye position, nearest pre-pulse fire
distance, minimum/maximum distance of actually extinguished cells, cabinet distance,
water use and fire count. Every paid pulse must remain at least 20 blocks from the
nearest still-burning fire; its water sum must equal the cabinet's measured loss.

The damage source ends at its 600-second watchdog with six fires and 96 missing
blocks. Do not infer successful recovery from this source. The owner approved
only its fire-suppression portion, at native speed, ending by 75 seconds. Frame
review selects [6,13] and [18,36] seconds: close suppression, a chronological
hard cut over quiet travel, then the camera change and active hose arc. The
edit ends as the fire count reaches about ten, before the long plateau. The repair source lasts 41.567 seconds: TNT detonates near 3.05,
restoration begins near 15.6 and finishes near 39.45 seconds. The edit retains
[0,5] and [15,41.4] seconds, cutting the fetch-materials wait while keeping
pre-blast, explosion, crater, active patching and final zero-missing HUD result.

Read-only evidence records exact baseline differences, original fire positions,
finite water/material counts and per-tick paid-pulse fire attribution. Client
samples require Damage Control mode, area marks, information-card targeting and
visible GUI throughout; they also observe native spray particles and connections.
Both tools must consume water and extinguish fires. Ambiguous simultaneous paid
tools fail verification rather than double-counting removed fires. No task calls
a suppression session, emits spray packets, forces work movement or places repair
blocks.

先前 300 秒停拍中“居民始终在墙外”的解释不准确：原片 1582 次采样中有 78 次
`extinguishing`，南侧也有入口。产品侧已定位为作业接近在距站位 0.9 格时结束，导致真实眼位
超出灭火器射程并反复 `NO_REACH`；`10755cd22` 将最终作业接近对齐到 0.1 格。
后续 `f6010c8db` 已用保守站位规划与普通 0.9 格到达替代精确对齐，并恢复原生远距离水带。
本次只修改拍摄布景、机位和证据，不修改 `mods/`。

## Shared location / 共同场景

All six scopes use the same settlement layout on fresh normal worlds:

- Seed `folkways-combat-river-1` (`-1369905320`).
- Survey origin X −1120, Z −1120; gate 46 blocks south.
- Tectonic 3.0.28, Terralith 2.6.2, Lithostitched 1.8.0.
- Native wall, road, arcade and field patterns, furnished taiga village templates.
- Fixed afternoon, clear weather, random spawning disabled; finite physical kits.

Only local foundations and native pattern earthworks alter generated terrain.
Initial staging enrolls residents, grants licences and places finite supplies.
Resident starting health, zombie health/speed and sunlight protection are
recorded. Enemy AI, projectiles, damage, suppression and block placement remain
native. No product source under `mods/` is changed for this film.

## Review and edit / 审查与剪辑

Extract source frames with FFmpeg and actually view them before approving a take:

```sh
python3 tests/promo/film/combat.py --stills
```

Inspect close RTS units and mode/group highlights; legible mixed weapons and SBW
raised arms; occupied stations, turret rotation, visible enemies, projectile
trails/impacts and deaths; the damage-control area/card, real extinguisher mist,
connected hose spray, falling fire counts and visible material repair. Reject
chat, toasts and failure overlays. Only after viewing the frames write
`context.visualReview = { approved: true, frames: [...], notes: "..." }`.
Reviewed `editPieces` and `previewPieces` describe native-speed source intervals;
chronological gaps are hard cuts, with no repeated or fabricated action.

```sh
npm run promo:combat:film
```

The editor refuses missing/unreviewed footage, unverified footage outside the
explicit partial-damage exception, old RTS input evidence, missing tool-water/fire
evidence, incomplete repair restoration, invalid interval order,
wrong resolution and absent native audio. It reuses `build.py` for subtitles and
uses a 220-pixel HUD/caption margin and top-right placement for damage and repair.
Damage shows only “已支持：机械动力 消防附加，可以使用灭火器和消防水带来灭火”.
Repair shows only “新功能：损害管制，可以划定区域，在遭受损失时自动修复”.
Preview source intervals must exist in the reviewed main edit. Their sum sets
the preview duration; pacing determines the selections, with no fixed-length
requirement or forced output truncation. The preview covers all four categories.

Outputs in `tests/out/promo/film/`:

- `folkways-combat.mp4`: 1920 × 1080, 60 FPS export from 30 FPS capture, native audio plus quiet music.
- `folkways-combat-preview.mp4`: 1920 × 1080, reviewed preview intervals at native speed, native audio plus quiet music.
- `combat-edit.json`: source intervals, timeline, runtime evidence and actual visual reviews.
- `combat-stills/`: reviewed source frames and final encoded-film/preview frames.

Generated videos, indexes and review frames are excluded from commits. A release
requires five verified sources, the explicitly approved partial damage source,
and actual source/export frame inspection;
older film output does not prove completion of this replacement batch.

## Current edit / 本轮成片

All six source videos remain unchanged. RTS, gate, flank and stations review
metadata now records inspected endpoints; capture/runtime evidence is unchanged. Damage
remains `context.verified=false`: 113→6 fires over the complete source, zero
restoration, extinguisher 30 mB / 27 fires and hose 120 mB / 80 fires. All paid
hose pulses remain at least 26.139 blocks from the nearest fire and account for
the full measured cabinet water loss. Within the selected source intervals ending at 36 seconds the
fire count falls to about ten. This is partial suppression evidence, not a
successful recovery run. The owner approved the fire-suppression part only;
recovery stalled, no repair is shown in that take.

Repair is independently verified and approved: maximumMissing=22,
after.missing=0, finite store inventory 128→106, 1247 frames and zero drops.
The native Damage Control card briefly lags placements but reads zero at the end.
No product rules, input semantics or battle UI are changed in this editing batch;
the earlier RTS design audit remains separate from engineering/export checks.
Residual-fire recovery diagnosis is deferred; it is not a release claim here.

| Shot     | Main edit source intervals (seconds, speed 1) | Preview source intervals (seconds) |
| -------- | --------------------------------------------- | ---------------------------------- |
| rts      | [0,40.5]                                      | [0,7], [11,17], [38,40.5]          |
| gate     | [0,10.5]                                      | [1,8]                              |
| flank    | [0,18]                                        | [1,8]                              |
| stations | [0,9], [37,41]                                | [0,7], [39,41]                     |
| damage   | [6,13], [18,36]                               | [8,12], [28,35]                    |
| repair   | [0,5], [15,41.4]                              | [2,5], [16,20], [26,29], [38,41.4] |

The RTS final movement settles near 40 s; cut at 40.5 instead of 43.1. Gate's
last kill is near 8.4 s, with fall/disappearance and particles around 9–9.5;
cut at 10.5 instead of 18. Flank's last kill is near 17 s and its last enemy
is still falling near 17.6, disappearing by 18. Keep that 18 s endpoint to avoid
cutting meaningful action. Stations retain their second-angle impact near 40.1,
then cut at 41 rather than holding to 44. Native-speed chronological intervals
and hard cuts remain; no action is repeated or sped up. RTS and stations preview
endpoints are shortened only as needed to remain within their main-film edits.

Damage remains 25 seconds: close extinguisher action, skip quiet 13–18 travel,
then the connected distant hose arc and rapid fire reduction, ending at 36 before
the ten-fire plateau. Repair remains 31.4 seconds: pre-detonation, explosion,
crater, skip the 5–15 fetch wait, then patching through the zero-missing ending.
Damage's gap retains 101 fires and 96 missing; repair's gap retains 22 missing
and zero fires. These are separate incidents; damage is not successful recovery.

The current preview totals 62.9 seconds: RTS 15.5, gate 7, flank 7, stations 9,
damage 11 and repair 13.4. No fixed-length check or forced 60-second trim applies.

Source indexes were backed up as `index.json.bak-20261027T074030Z` before original
approval edits, `index.json.bak-20261027T074700Z` before damage/repair pacing edits,
and `index.json.bak-20261027T075920Z` before the four new endpoint reviews.
Existing approval fields and runtime evidence remain; `visualReview.tailReview`
records endpoint inspection. No new gameplay validation is claimed.

Actually viewed new `rts-tail-source.jpg`, `rts-stop-fine.jpg`,
`gate-tail-source.jpg`, `gate-tail-fine.jpg`, `flank-tail-source.jpg` and
`stations-tail-source.jpg` under `combat-stills/`, with individual source PNGs.
Earlier damage/repair source review remains in `damage-pacing-source.jpg` and
`repair-pacing-source.jpg`. Frame review informs editorial choices; it does not
replace runtime evidence or the earlier independent RTS design audit.

## Music / 背景音乐

Use `minecraft/sounds/records/pigstep.ogg` from the existing game asset store,
starting at 0 seconds. Its driving rhythm suits the defence/firefight sequence
and gives the action momentum. It is not used by the other film scripts; those
use `aria_math`, `comforting_memories` and `dreiton`. The local index and physical
objects also contain these candidates:

- Nether: `ballad_of_the_cats`, `concrete_halls`, `chrysopoeia`, `dead_voxel`,
  `rubedo`, `so_below`, `warmth`.
- Records: `11`, `13`, `5`, `blocks`, `cat`, `chirp`, `creator`,
  `creator_music_box`, `far`, `mall`, `mellohi`, `otherside`, `pigstep`,
  `precipice`, `relic`, `stal`, `strad`, `wait`, `ward`.

Other plausible choices were `precipice` for a longer dramatic arc, `rubedo` or
`concrete_halls` for a darker atmosphere, and `otherside` for a brighter tone.
Pigstep provides the chosen rhythmic combat mood. It lasts 148.235 s and covers
this edit without looping; looping supports shorter override files.

`track_file` uses the same local-file/asset-index lookup as `assemble.py`, with
`PROMO_MUSIC` (asset name or file path) and `PROMO_MUSIC_START` overrides. Native
game audio stays at gain 1. Music gain is 0.12 (−18.42 dB), versus assemble.py's
0.9, and ducks against game audio: threshold 0.02, ratio 6:1, attack 5 ms,
release 250 ms. Music fades in over 1.5 seconds and out over 3 seconds. The
sum uses a 0.85 ceiling limiter without automatic makeup gain. Both exports
start a continuous music bed independently; the preview is cut from the native
intermediate before mixing, so music never jumps across picture cuts or mixes
twice. Native effects keep their hard-cut timing, and video is stream-copied
during the final audio mix.

## Final export review / 最终导出审查

`python3 tests/promo/film/combat.py` completed; a final audio-only remux lowered
the limiter ceiling to 0.85 for AAC transient headroom. Video stream hashes match
the reviewed native intermediates after mixing. FFprobe reports main container
138.421029 s (138.400 s video/audio; video starts about 21 ms into the container),
1920×1080 at 60 FPS. Preview is 62.900 s at 1920×1080, 30 FPS. Both have
stereo AAC at 48 kHz. All source MP4 hashes match their pre-edit values.

| Shot     |   Start | Duration |
| -------- | ------: | -------: |
| rts      |   0.0 s |   40.5 s |
| gate     |  40.5 s |   10.5 s |
| flank    |  51.0 s |   18.0 s |
| stations |  69.0 s |   13.0 s |
| damage   |  82.0 s |   25.0 s |
| repair   | 107.0 s |   31.4 s |

Actually viewed `round3-boundaries.jpg`, `round3-internal-cuts.jpg`,
`round3-captions.jpg` and `round3-preview-captions.jpg` in `combat-stills/`, plus
the individually retained source tail frames. Film frame pairs cover boundaries
40.5, 51, 69, 82 and 107 s, and internal cuts at 78, 89, about 92.9 and 112 s.
Changed cuts preserve stopped residents, the last gate deaths, the final flank
fall and the late station impact. Damage and repair caption starts show exactly
their own single lines in both exports. The restored ending still shows zero
missing. No failure overlay is visible in reviewed frames.

FFmpeg `volumedetect` checks on main-film gunfire (42.5–46.5 s) and the repair
explosion (109.5–112.5 s) compare native, ducked music stem and final AAC mix:

| Stretch   | Native mean | Music mean | Mixed mean | Mixed peak |
| --------- | ----------: | ---------: | ---------: | ---------: |
| Gunfire   |  −18.1 dBFS | −47.5 dBFS | −18.1 dBFS |  −0.4 dBFS |
| Explosion |  −26.0 dBFS | −36.3 dBFS | −25.8 dBFS | −10.0 dBFS |

The native effects exceed the music by 29.4 dB and 10.3 dB respectively. Preview
checks also retain effects: gunfire (16.5–20.5 s) mean −18.2 / peak −0.6 dBFS;
explosion (49.8–51.8 s) mean −24.2 / peak −9.8 dBFS. These are numerical audio
sanity checks, not a claim of an audition or new gameplay validation.

`combat-edit.json` records the actual music selection, mix parameters, revised
timeline, source reviews, exported-frame review and loudness measurements. Python
compilation, chronological interval/preview containment checks, source/video hash
checks and `git diff --check` pass. Prettier was run on this document. No commit,
Minecraft launch or edit under `tests/visual/` was performed.
