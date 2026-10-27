# TACZ firearms

[Documentation](README.md) · [简体中文](../zh-CN/tacz.md)

Install `folkways-tacz-*.jar` beside Core, Vanilla and the **unofficial NeoForge
1.21.1 TACZ port, exactly 1.1.8-hotfix-r7**. The download and SHA-512 are pinned in
`blockwright.toml`. This optional integration has no RTS dependency.

In the Colony Book's **Loadout profiles** page, create a profile and use
**+ Add → Weapon variants** to pick a supported gun model. Model choices preserve
GunId and accept conventional attachment variants without pinning loaded rounds or
chamber state; unsafe explosive or incendiary variants remain unusable. Assign
residents through **Assign residents…**. Legacy exact-item and enchantment pins
load as plain items/tags; old held-gun attachment constraints are removed while
model and ammunition identities remain.

Adding a firearm prefills one matching ammunition supply entry, identified by
`custom_data.AmmoId`, with three magazines as a suggestion. Set its target and
resupply threshold in the profile and assign residents. These player-set amounts
control later collection; there are no global `targetRounds` or `lowWaterRounds`
settings. Loaded magazines and chambers count toward the total. Refill starts
strictly below the threshold and takes finite stock from reachable registered
storage. No profile is inferred from manually given guns. Gun collection uses
profile equipment rather than trade tool permissions.

Post an assigned resident at a drawn **Sentry post** zone. Empty guns and missing
ammunition do not prevent assignment. The resident collects its profile equipment
and fires when a hostile is in sight and effective range, without chasing it.
Profile and supply views show unavailable equipment and ammunition shortages;
post cards show effective duty and blocked shots, and can show watching during stockouts. Ordinary drawn patrollers can use
the same firearm sessions. Optional RTS GUARD retains its existing twelve-block
slot leash; TACZ does not implement RTS explicit-target attack leases.

The item-usage provider reports direct ranged fire, the gun's AmmoId, magazine
capacity, loaded rounds, and cycle ticks from RPM, manual bolt timing and burst
timing. Effective range is bounded by bullet speed × lifetime, the server cap and
any per-gun cap. Metadata does not override TACZ's actual firing/reload timers.

The server writes `folkways-tacz-server.toml`:

| Setting               | Default                         | Effect                                                                                                 |
| --------------------- | ------------------------------- | ------------------------------------------------------------------------------------------------------ |
| `allowedGuns`         | `[]`                            | Compatible built-in guns; an explicit list restricts IDs and may opt in conventional gun packs         |
| `rangeCap`            | `24`                            | Maximum firing distance, also bounded by gun data                                                      |
| `gunRangeCaps`        | `tacz:m1911=16`, `tacz:m870=12` | Per-gun caps bounded by `rangeCap`                                                                     |
| `protectColonyAllies` | `true`                          | Protect same-colony residents and colony pets from colony bullets, including bullets already in flight |

Allied residents, players and colony pets crossing the firing corridor cause the
resident to hold fire. Stray colony rounds stop on blocks without breaking glass
or activating blocks. Explosive, incendiary, fuel, virtual/infinite ammunition,
charge weapons and unknown gun scripts are unsupported. Attachment effects are
checked on equip and discharge; final projectile payloads are checked before
spawn. TACZ performs drawing, aiming, shooting, bolting and reloads through
`IGunOperator`; the reload mixin bounds script transfers by rounds actually taken
from the resident's pack. Delayed burst tasks retain the original session
generation and recheck live permission, sight, range and friendly-fire clearance.

Portable guns use `WeaponAdapter`/`WeaponSession`, with a live `spottedTarget`
permission from sentries. They require their own line of sight and provide no
indirect fire. They do not register a fixed `WeaponStations` implementation or
change colony spotting, post capacity, manual-order precedence or RTS inputs.

Holding poses cover player skins and common Geo/maid arm bone names, including
actual held items on Geo arms. TACZ player-only reload animations, model-specific
clips, other resident body kinds, fixed gun stations and indirect guns remain
outside this port.

```sh
./gradlew -PtaczTests=true -PgameTestMod=tacz runGameTestServer
./gradlew -PtaczTests=true runGameTestServer
xvfb-run -a npm run test:e2e -- tests/e2e/tacz-combat.spec.ts --workers=1
```

Blockwright profiles `tacz` and `tacz_models` install the integration; the latter
also loads Touhou Little Maid for skin, Geo and maid pose acceptance. Native TACZ
timers use milliseconds, so accelerated GameTest ticks alone cannot advance them.

## Verified port results

Linux, JDK 21, with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` and
`GRADLE_USER_HOME=/tmp/folkways-sentry-gradle`:

| Command                                                                     | Result                               |
| --------------------------------------------------------------------------- | ------------------------------------ |
| `./gradlew build`                                                           | PASS; 50 tasks, no failures          |
| `./gradlew runGameTestServer`                                               | PASS; 1,233 required tests, 0 failed |
| `./gradlew -PtaczTests=true runGameTestServer`                              | PASS; 1,274 required tests, 0 failed |
| `./gradlew -PtaczTests=true -PgameTestMod=tacz runGameTestServer`           | PASS; 41 required tests, 0 failed    |
| `npm run test:i18n`                                                         | PASS; 1 file, 8 cases, 0 failures    |
| `npm run typecheck`                                                         | PASS; 7 projects, 0 errors           |
| `npm run format:md:check`                                                   | PASS; no files needing formatting    |
| `xvfb-run -a npm run test:e2e -- tests/e2e/tacz-combat.spec.ts --workers=1` | PASS; 1 test, 0 failed, 37.9 seconds |

The client run also used `LIBGL_ALWAYS_SOFTWARE=1`. It verified actual target
damage from sentry duty and actual held guns/aiming poses for skin, Geo and maid
appearances, with a screenshot under `tests/out/e2e/playwright/` and pose JSON
attached by the spec.
It does not exercise the new profile variant dialog interactively. That acceptance
remains unrun. There are no failing test names in the final runs.

Old PR tests that inferred a manual gun's global 32/8 policy now exercise assigned
profiles, strict editable thresholds and sentry collection. RTS retreat fixture
calls were replaced with session preemption, profile revocation and unposting;
the module never installs RTS. Additional cases verify metadata, component-bearing
profile UI actions, attachment identity across reloads, own sight/range, live
observation revocation and disenrollment. Static design approval is recorded in
[combat architecture](combat-architecture.md#tacz-profiles-and-sentry-integration).
