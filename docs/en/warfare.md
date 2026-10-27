# Superb Warfare

[Gameplay](gameplay.md) · [Combat architecture](combat-architecture.md) · [简体中文](../zh-CN/warfare.md)

Install `folkways_warfare` beside Core, Vanilla, Superb Warfare **0.8.9.2** and
Kotlin for Forge **5.12.0**. Both native jars are pinned by SHA-512 in
`blockwright.toml`. The optional `warfare` profile installs them together. RTS is
optional and is not a dependency of this integration.

## Equipment profiles

Open the colony book's **Loadout profiles** page, create a profile and add one of
these guns as a weapon entry. The item provider prefills an editable ammunition
supply from magazine size and cycle time. Set its **Target** and **Restock below**
amounts, assign the profile to residents, and put actual guns and ammunition in
registered colony stores within 32 blocks of their collection position.

| Gun      | Ammunition   | Effective range |
| -------- | ------------ | --------------- |
| Glock 17 | Handgun ammo | 24 blocks       |
| MP5      | Handgun ammo | 32 blocks       |
| AK-47    | Rifle ammo   | 48 blocks       |
| M4       | Rifle ammo   | 48 blocks       |
| M870     | Shotgun ammo | 20 blocks       |
| SVD      | Sniper ammo  | 64 blocks       |

Profiles own quantities; there is no global sixteen-round allowance. Strict
thresholds, partial refill, storage permissions, reservations and exact component
matching use Vanilla's existing collection system. Trade tool lists do not
configure combat equipment.

The actual gun is lent to the resident's hand. `GunData.shoot` consumes finite
loaded rounds first, then borrows one real loose round when empty. Loaded rounds
count toward profile stock. Native heat and bolt state tick on the real stack;
returning it to the pack cannot reset RPM or pump time. Failed entity insertion
refunds the original loose round, including components. Names and other accepted
item components survive hand settlement.

While the combat session holds the gun in the resident's actual main hand, skin
residents use SBW's native third-person two-arm pose, including head aim. Returning
the gun to the pack clears the pose. This follows the same equipment synchronization
as TACZ residents; a raised gun alone does not mean a shot has passed the server's
sight, ammunition and friendly-fire checks.

Only the six supported, predictable gun configurations are accepted. Perks,
property overrides, explosive or penetrating effects, custom projectiles,
automatic reload and unsupported ammunition consumption are refused. An unsafe
change to an already held weapon closes its session before discharge. Direct
handheld fire requires hostility, range, own sight and a clear friendly corridor.

## Independent weapon stations and shell supply

1. Place a supported stationary weapon, unlock it and select ordinary mortar
   shells or the cannon's **HE** ammunition in SBW. AP and TOW are excluded.
2. With the colony book in point/member mode, stand within eight blocks and
   explicitly include the weapon entity as an **Independent weapon station**.
   Keep a walkable route and clear standing space outside the weapon's actual hull.
   Drawing a sentry area around a weapon does not include or staff it.
3. Give combat residents a loadout profile. Any assigned profile qualifies as a
   station operator, including melee or supply-only profiles.
4. Add the station's shells as a **Supply** entry. For a mortar, choose
   `superbwarfare:mortar_shell`, then set Target and Restock below to the amounts
   you want. For Mk42 HE, choose `superbwarfare:large_shell_he`. Component-pinned
   supply entries collect only the selected physical variant.
5. Assign profiles and stock registered stores. Each enrolled weapon automatically
   takes one idle combat resident; no per-weapon profile or resident selection is needed.
   The operator collects their own profile's shell target through normal logistics.
   Valid incumbents stay; vacancies at stations fill before zones/routes, with UUID tie-breaking.

The independent-post list accepts mortars, Mk42, BL132, Mle1934, the stopped
FH77BW howitzer and HPJ11; execution still requires the known safe HE
cannon-shell configuration. Tanks, aircraft, boats, moving vehicles, guided TOW, AP,
incendiary, cluster and unknown projectiles are excluded. Existing occupants keep
their seats. Vanilla grants one exclusive lease to that station's automatic operator.
Mortars are operated standing beside them; other weapons use the actual turret
seat, including the FH77BW's second seat.

Operators walk to an external approach cell through normal navigation. The whole
resident must fit without intersecting the cannon or another blocking entity;
candidates form the first adjacent ring, including corner cells. Unreachable
cells are skipped in favor of reachable alternatives. Arrival immediately boards
the weapon, even without an enemy, with no further eye-to-hitbox distance check. If every route is blocked,
travel reports its usual no-route failure and keeps the operator claim.
Clear the route and standing space to allow a later attempt. Mortars still fire
from beside the weapon without taking a passenger seat.

Mortars and FH77BW declare indirect fire. They can use a fresh colony
`SpottedTargets` observation made by another defending resident or route
patroller, beyond the operator's own sight. Aim remains the immutable observed
position; death, unload, lost hostility, expiry or displacement over two blocks
invalidates it. Mk42 and other direct stations require their own line of fire.
No station independently scans hidden targets or chases them.

SBW's native vehicle firing and mortar delay remain in use. Preparation keeps no
shell copy in the station between ticks. At actual emission, Vanilla rechecks the
same lease, live duty, observation, range and direct-fire requirements. The
integration checks the actual muzzle direction, full ballistic path, dispersion
and predicted impact. Entity protection uses **twice** the configured blast
radius; block protection separately uses SBW's maximum flattened block radius
(**0.6 times** the configured radius). Both include the dispersion allowance.
Allies, pets, players and own-colony blocks or stores at risk hold fire; unknown
or unloaded paths are refused.

One matching physical pack shell is reserved immediately before emission and
consumed only after a projectile is inserted successfully. Refusal refunds it.
Native ammunition lent during that transaction is restored in `finally`.
Retargeting, stale observations or an ally entering during preparation cannot
reuse a queued shot's old authority. The **Sentry posts** overview reports blocked shots, missing ammunition, unavailable
stations, occupied leases, control suspension and missing footing. **Withdraw
station** releases its operator claim and lease even while the entity is unloaded. A
co-located replacement weapon cannot inherit the old entity UUID's staffing.

Retreat or another validated direct order, unposting, profile loss, revoked
patrolling permission, death, unloading, station/zone removal and colony closure
release the duty and its owned seat. Invalid commands preserve the current seat.
Vanilla persists temporary seat identity, but leases and pending fire remain
transient. After a crash or snapshot restore, an orphan marker ejects only the
recorded seat; unrelated player riding remains intact. A marked restored mortar
preparation is cancelled. Already launched projectiles retain SBW physics.

Existing saves do not automatically include weapons formerly operated through a
sentry area. Old area slots become allowed-profile unions and total headcounts;
resident pins are dropped. Existing enrolled stations keep enrollment and become
automatic; their old profile posts are discarded. Live leases and prepared shots do not persist.

## Observation gear

In **Loadout profiles**, choose **+ Add → Observation gear** and select the vanilla
spyglass. This creates an ordinary supply entry with target/restock threshold
1/1. A resident needs both that profile requirement and the matching real item
in their pack. Missing gear appears in the existing equipment and stock shortages
and is collected from registered stores. Merely assigning the profile grants no
bonus. Multiple spyglasses do not stack.

Sight is computed for each observer–target pair, using these server settings in
`sentries`: baseline `defenderSightRadius` **32**, plus vigilance, plus
`sightHeightPerBlock` **1.5** for each positive eye-height block, counting at most
`sightHeightLimit` **16** blocks. Being below the target adds no bonus or penalty.
No surrounding-ground search is needed.

Multiply this by the target's light factor, interpolated from `darkSightMultiplier`
**0.25** at effective light at or below `darkSightLightLevel` **4**, to 1 at
`fullSightLightLevel` **12**. The dark threshold is capped below the full-light
threshold; setting it to 0 restores the older interpolation. The vanilla
brightness query includes block light and sky light reduced by time, rain and
thunder. Night vision on the observer, fire or Glowing on the target, and native
luminous creatures bypass darkness. The data-pack tag `folkways:luminous_targets`
includes blaze, glow squid and magma cube and can be extended.

A profile-required observation item actually in the pack applies
`observationSightMultiplier` **2**, once, to that height/light-adjusted distance.
The result is clamped between `minimumSightRadius` **3** and
`maximumSightRadius` **128**. The minimum never bypasses a wall, and the maximum
also bounds scans. A lower configured maximum overrides the minimum. Set the
observation multiplier to 1 to disable magnification; the old fixed-addition
`observationSightBonus` setting is removed and its value is not converted.

With no vigilance bonus, the defaults give 32 blocks on bright flat ground,
44 with an eight-block eye-height advantage, and 88 from that wall with physical
optics (64 on flat ground). Effective light 0–4, including open night sky, gives
8 on flat ground without optics. Other coefficients remain unchanged. The maximum
possible scan/ring radius is 56 without optics or 112 with optics, still capped at 128. Existing saves retain their configured baseline; change their server setting
to 32 explicitly to adopt the new default.

This affects discovery, not weapon range, and applies to scouts and mortar/FH77BW
forward observers through ordinary shared sightings. Changing gear, effects or
illumination affects the next check immediately. Existing genuine reports keep
their normal TTL and displacement checks. Sentry circles indicate the maximum
possible sight for that observer, not guaranteed discovery of every target inside.

The pinned SBW 0.8.9.2 jar contains no separate binocular/spyglass item. Optional
`superbwarfare:binoculars` and `superbwarfare:spyglass` appear as choices only if
actually registered by an installed version; no missing item is created.

## Explosion protection

Automatic station launch requires the mob-griefing permission hook. Resident
projectile explosions check permission and impact safety again before damage.
A cancellable NeoForge `ExplosionEvent.Start` runs before SBW touches blocks or
entities and is not posted twice. Each native block-destruction tier passes its
candidates through `ExplosionEvent.Detonate` before mutation; SBW's entity
Detonate processing remains active. These patches apply to resident shots.
Player-operated SBW weapons retain their native behavior.

## Integration and verification

`Weapons.register` supplies real weapon execution; `LoadoutRegistry.register`
supplies item-usage metadata. `WeaponStations.register` discovers stable station objects; independent posts
filter them by the explicitly held entity UUID. Ordinary sentry areas never
acquire them. Vanilla owns the lease, delayed discharge
transaction, profile logistics and persisted temporary seat marker. Neither
Vanilla nor Warfare imports RTS. Optional RTS recognizes Vanilla-owned seats for
selection and validates a complete command before releasing them.

Run the commands in [Testing](testing.md). Warfare's focused GameTests exercise
native firing, finite magazines and supplies, cadence, unsafe variants, shared
patrol spotting, impact holds, delayed cancellation, shell logistics, seat
lifecycle, snapshot release and explosion events. Static design review and server
GameTests do not establish real-client gameplay acceptance. Run
`npm run visual:take -- weapon-poses.spec.ts --workers=1` for the AK-47/M870 skin
resident screenshots beside a TACZ M4A1, native arm-rotation assertions and session
release checks. Post cards, other appearance backends and every supported
emplacement remain separate visual acceptance work.
