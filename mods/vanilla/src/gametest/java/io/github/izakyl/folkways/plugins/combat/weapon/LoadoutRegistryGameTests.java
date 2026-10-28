package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways")
@PrefixGameTestTemplate(false)
public final class LoadoutRegistryGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(LoadoutRegistryGameTests.class);
    }

    @GameTest(template = "empty")
    public static void allocationDoesNotCountOneUnitTwice(GameTestHelper helper) {
        var first = LoadoutProfile.Entry.supply(ItemSpec.of(Items.ARROW), 4, 1);
        var second = LoadoutProfile.Entry.supply(ItemSpec.of(Items.ARROW), 4, 1);
        var profile = new LoadoutProfile(UUID.randomUUID(), "Archers", List.of(first, second), Optional.empty());
        var carried = List.of(new ItemStack(Items.ARROW, 5));
        for (int pass = 0; pass < 3; pass++) {
            var missing = PatrolSupplies.allocate(profile, carried, slot -> ItemStack.EMPTY);
            helper.assertValueEqual(missing.get(first.id()), 0, "first entry receives its four arrows");
            helper.assertValueEqual(missing.get(second.id()), 3, "second entry receives only the remaining arrow");
            helper.assertValueEqual(carried.getFirst().getCount(), 5, "allocation does not mutate its source");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void absentComponentTypesRemainRawThroughProfileRenameDuplicateAndSave(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        try {
            CompoundTag item = new CompoundTag();
            item.putString("item", "absent:rifle");
            CompoundTag values = new CompoundTag();
            values.putString("absent:gun_state", "keep this exact identity");
            CompoundTag components = new CompoundTag();
            components.put("values", values);
            item.put("components", components);
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", UUID.randomUUID());
            entry.putString("kind", "WEAPON");
            entry.put("items", item);
            entry.putInt("target", 1);
            entry.putInt("threshold", 1);
            ListTag entries = new ListTag();
            entries.add(entry);
            CompoundTag profile = new CompoundTag();
            profile.putUUID("id", UUID.randomUUID());
            profile.putString("name", "Unavailable rifle");
            profile.put("entries", entries);
            ListTag profiles = new ListTag();
            profiles.add(profile);
            CompoundTag saved = new CompoundTag();
            saved.putInt("version", 3);
            saved.put("profiles", profiles);
            colony.keep(LoadoutProfiles.SAVED, saved);
            var loadouts = new PatrolSupplies(colony).profiles();
            var loaded = loadouts.all().getFirst();
            helper.assertFalse(
                loaded.entries().getFirst().available(), "missing component codec creates an"
                + " unavailable entry");
            loadouts.replace(new LoadoutProfile(loaded.id(), "Renamed", loaded.entries(), loaded.legacy()));
            loadouts.duplicate(loaded.id());
            var rows = colony.kept(LoadoutProfiles.SAVED).getList("profiles", Tag.TAG_COMPOUND);
            helper.assertValueEqual(rows.size(), 2, "duplicate keeps an independent profile");
            for (int n = 0; n < rows.size(); n++) {
                helper.assertValueEqual(
                    rows.getCompound(n).getList("entries", Tag.TAG_COMPOUND).getCompound(0).getCompound("items"),
                    item,
                    "unknown item/component data survives each save unchanged");
            }
            helper.assertValueEqual(
                new PatrolSupplies(colony).profiles().all().size(),
                2,
                "unavailable component entries load again safely");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void retiredPinsMigrateBeforeDecodeAndKeepTypedVariants(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        try {
            var profiles = new PatrolSupplies(colony).profiles();
            var profile = profiles.create("Old pins");
            profiles.add(profile.id(), LoadoutProfile.Entry.supply(ItemSpec.of(Items.ARROW), 8, 2));
            CompoundTag saved = colony.kept(LoadoutProfiles.SAVED).copy();
            saved.putInt("version", 3);
            CompoundTag row = saved.getList("profiles", Tag.TAG_COMPOUND).getCompound(0)
                .getList("entries", Tag.TAG_COMPOUND).getCompound(0);
            CompoundTag exact = ItemSpec.exact(new ItemStack(Items.IRON_SWORD))
                .save(helper.getLevel().registryAccess());
            CompoundTag enchanted = ItemSpec.of(Items.BOW).save(helper.getLevel().registryAccess());
            CompoundTag components = new CompoundTag(), values = new CompoundTag();
            values.putString("minecraft:enchantments", "obsolete undecodable enchantment");
            components.put("values", values);
            enchanted.put("components", components);
            CompoundTag variants = new CompoundTag();
            ListTag alternatives = new ListTag();
            alternatives.add(exact);
            alternatives.add(enchanted);
            var arrows = TagKey.create(Registries.ITEM, ResourceLocation.parse("minecraft:arrows"));
            CompoundTag enchantedTag = ItemSpec.of(arrows).save(helper.getLevel().registryAccess());
            enchantedTag.put("components", components.copy());
            alternatives.add(enchantedTag);
            variants.put("anyOf", alternatives);
            row.put("items", variants);
            colony.keep(LoadoutProfiles.SAVED, saved);
            var loaded = new PatrolSupplies(colony).profiles().get(profile.id()).orElseThrow();
            helper.assertValueEqual(loaded.entries().getFirst().items(),
                ItemSpec.anyOf(List.of(ItemSpec.of(Items.IRON_SWORD), ItemSpec.of(Items.BOW), ItemSpec.of(arrows))),
                "exact and enchantment constraints become plain alternatives before decoding");
            helper.assertValueEqual(colony.kept(LoadoutProfiles.SAVED).getInt("version"), 4,
                "migration immediately persists the new format");
            helper.assertValueEqual(new PatrolSupplies(colony).profiles().get(profile.id()).orElseThrow(), loaded,
                "migrated entries reload without further changes");
            CompoundTag typed = ItemSpec.of(Items.STICK).save(helper.getLevel().registryAccess());
            CompoundTag identity = new CompoundTag();
            identity.putString("GunId", "tacz:m1911");
            identity.putString("AttachmentSCOPE", "old held attachment");
            CompoundTag typedValues = new CompoundTag(), typedComponents = new CompoundTag();
            typedValues.put("minecraft:custom_data", identity);
            typedComponents.put("values", typedValues);
            typed.put("components", typedComponents);
            CompoundTag converted = LoadoutProfiles.withoutPins(typed);
            CompoundTag actual = converted.getCompound("components").getCompound("values")
                .getCompound("minecraft:custom_data");
            helper.assertValueEqual(actual.getString("GunId"), "tacz:m1911", "typed gun identity survives");
            helper.assertFalse(actual.contains("AttachmentSCOPE"), "retired held attachments no longer constrain");
            identity.remove("GunId");
            identity.remove("AttachmentSCOPE");
            identity.putString("AmmoId", "tacz:45acp");
            helper.assertValueEqual(LoadoutProfiles.withoutPins(typed), typed, "typed ammunition is unchanged");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void extensionMigrationUsesItsOwnLegacySupplyQuantities(GameTestHelper helper) {
        var id = ResourceLocation.fromNamespaceAndPath("folkways", "test_legacy_" + UUID.randomUUID());
        LoadoutRegistry.register(
            id,
            new LoadoutProvider() {
                public boolean accepts(ItemStack stack) {
                    return stack.is(Items.STICK);
                }

                public boolean ranged(ItemStack stack) {
                    return true;
                }

                public double range(ItemStack stack) {
                    return 32;
                }

                public Optional<ItemSpec> legacyWeapon(CompoundTag options) {
                    return Optional.of(ItemSpec.of(Items.STICK));
                }

                public List<LegacySupply> legacySupplies(CompoundTag options, ItemStack weapon) {
                    return List.of(
                        new LegacySupply(
                        ItemSpec.of(Items.FIREWORK_ROCKET),
                        options.getInt("target"),
                        options.getInt("threshold")));
                }
            });
        var colony = Colonies.mint(helper.getLevel().getServer());
        try {
            CompoundTag row = new CompoundTag();
            row.putUUID("resident", UUID.randomUUID());
            row.putString("loadout", id.toString());
            CompoundTag options = new CompoundTag();
            options.putInt("target", 96);
            options.putInt("threshold", 24);
            row.put("options", options);
            ListTag rows = new ListTag();
            rows.add(row);
            CompoundTag saved = new CompoundTag();
            saved.putInt("version", 2);
            saved.put("loadouts", rows);
            colony.keep(LoadoutProfiles.SAVED, saved);
            var profile = new PatrolSupplies(colony).profiles().assigned(row.getUUID("resident")).orElseThrow();
            var supply =
                profile.entries().stream()
                .filter(entry -> entry.kind() == LoadoutProfile.Kind.SUPPLY)
                .findFirst()
                .orElseThrow();
            helper.assertTrue(
                supply.target() == 96 && supply.threshold() == 24,
                "extension migration preserves its own quantities instead of native eight-arrow"
                + " defaults");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void identicalLegacySelectionsDeduplicateAndUnknownProfilesKeepOptionsWhenRenamed(
        GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        try {
            ListTag rows = new ListTag();
            for (String name : List.of("BOW", "BOW", "folkways:missing_integration")) {
                CompoundTag row = new CompoundTag();
                row.putUUID("resident", UUID.randomUUID());
                row.putString("loadout", name);
                CompoundTag options = new CompoundTag();
                if (name.contains(":")) {
                    options.putString("gun", "example:rifle");
                    options.putInt("magazine", 30);
                }
                row.put("options", options);
                rows.add(row);
            }
            CompoundTag old = new CompoundTag();
            old.putInt("version", 2);
            old.put("loadouts", rows);
            colony.keep(LoadoutProfiles.SAVED, old);
            PatrolSupplies supplies = new PatrolSupplies(colony);
            var profiles = supplies.profiles();
            helper.assertValueEqual(profiles.all().size(), 2, "identical selections become one"
                + " shared profile");
            var bow = profiles.assigned(rows.getCompound(0).getUUID("resident")).orElseThrow();
            helper.assertValueEqual(
                profiles.assigned(rows.getCompound(1).getUUID("resident")).orElseThrow().id(),
                bow.id(),
                "both residents receive the same profile ID");
            helper.assertTrue(
                bow.entries().stream()
                .anyMatch(
                entry ->
                    entry.kind() == LoadoutProfile.Kind.SUPPLY
                        && entry.target() == 8
                        && entry.threshold() == 8),
                "legacy ammo amount and strict threshold are explicit profile values");
            var unknown = profiles.assigned(rows.getCompound(2).getUUID("resident")).orElseThrow();
            profiles.replace(new LoadoutProfile(unknown.id(), "Preserved"
                + " gun", unknown.entries(), unknown.legacy()));
            var restored = new PatrolSupplies(colony).profiles();
            var selection = restored.get(unknown.id()).orElseThrow().legacy().orElseThrow();
            helper.assertValueEqual(
                selection.id().toString(), "folkways:missing_integration", "unknown provider ID"
                + " round-trips");
            helper.assertValueEqual(
                selection.options().getInt("magazine"), 30, "unknown extension options"
                + " round-trip without loss");
            helper.assertValueEqual(restored.all().size(), 2, "second load never repeats"
                + " migration");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void legacyEnumsMigrateAndAbsentExtensionsKeepTheirOptions(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        try {
            ListTag rows = new ListTag();
            for (String name : List.of("MELEE", "BOW", "CROSSBOW", "folkways:absent_gun")) {
                CompoundTag row = new CompoundTag();
                row.putUUID("resident", UUID.randomUUID());
                row.putString("loadout", name);
                if (name.contains(":")) {
                    CompoundTag options = new CompoundTag();
                    options.putString("gun", "example:rifle");
                    row.put("options", options);
                }
                rows.add(row);
            }
            CompoundTag old = new CompoundTag();
            old.put("loadouts", rows);
            ResourceLocation key = ResourceLocation.fromNamespaceAndPath("folkways", "patrol_loadouts");
            colony.keep(key, old);
            PatrolSupplies supplies = new PatrolSupplies(colony);
            helper.assertValueEqual(
                supplies.preference(rows.getCompound(0).getUUID("resident")).orElseThrow(),
                LoadoutRegistry.MELEE,
                "legacy melee ID");
            helper.assertValueEqual(
                supplies.preference(rows.getCompound(1).getUUID("resident")).orElseThrow(),
                LoadoutRegistry.BOW,
                "legacy bow ID");
            helper.assertValueEqual(
                supplies.preference(rows.getCompound(2).getUUID("resident")).orElseThrow(),
                LoadoutRegistry.CROSSBOW,
                "legacy crossbow ID");
            var unknown = supplies.preference(rows.getCompound(3).getUUID("resident")).orElseThrow();
            helper.assertTrue(unknown.provider().isEmpty(), "absent integrations remain inactive");
            helper.assertValueEqual(unknown.options().getString("gun"), "example:rifle", "options"
                + " survive"
                + " migration");
            helper.assertValueEqual(colony.kept(key).getInt("version"), 4, "migration is written"
                + " explicitly");
            PatrolSupplies restored = new PatrolSupplies(colony);
            helper.assertValueEqual(
                restored.preference(rows.getCompound(3).getUUID("resident")).orElseThrow(),
                unknown,
                "unknown ID survives another save/load");
            unknown.options().putString("gun", "mutated");
            helper.assertValueEqual(
                unknown.options().getString("gun"), "example:rifle", "caller cannot mutate"
                + " persisted"
                + " options");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }
}
