package net.minestom.codegen;

import net.minestom.codegen.color.DyeColorGenerator;
import net.minestom.codegen.fluid.FluidGenerator;
import net.minestom.codegen.particle.ParticleGenerator;
import net.minestom.codegen.recipe.RecipeTypeGenerator;
import net.minestom.codegen.util.GenericEnumGenerator;
import net.minestom.codegen.worldevent.WorldEventGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;

public class Generators {
    private static final Logger LOGGER = LoggerFactory.getLogger(Generators.class);

    public static void main(String[] args) {
        if (args.length != 1) {
            LOGGER.error("Usage: <target folder>");
            return;
        }
        File outputFolder = new File(args[0]);

        // Special generators
        new DyeColorGenerator(resource("dye_colors.json"), outputFolder).generate();
        new ParticleGenerator(resource("particles.json"), outputFolder).generate();
        new ConstantsGenerator(resource("constants.json"), outputFolder).generate();
        new RecipeTypeGenerator(resource("recipe_types.json"), outputFolder).generate();
        new GenericEnumGenerator("net.minestom.server.recipe.display", "RecipeDisplayType",
                resource("recipe_display_types.json"), outputFolder).generate();
        new GenericEnumGenerator("net.minestom.server.recipe.display", "SlotDisplayType",
                resource("slot_display_types.json"), outputFolder).generate();
        new GenericEnumGenerator("net.minestom.server.recipe", "RecipeBookCategory",
                resource("recipe_book_categories.json"), outputFolder).generate();
        new GenericEnumGenerator("net.minestom.server.item.component", "ConsumeEffectType",
                resource("consume_effects.json"), outputFolder).packagePrivate().generate();
        new GenericEnumGenerator("net.minestom.server.command", "ArgumentParserType",
                resource("command_arguments.json"), outputFolder).generate();
        new GenericEnumGenerator("net.minestom.server.entity", "VillagerType",
                resource("villager_types.json"), outputFolder).generate();
        new WorldEventGenerator("net.minestom.server.worldevent", "WorldEvent",
                resource("world_events.json"), outputFolder).generate();

        var generator = new CodeGenerator(outputFolder);

        // Static registries
        generator.generate(resource("blocks.json"), "net.minestom.server.instance.block", "Block", "BlockImpl", "Blocks");
        generator.generate(resource("items.json"), "net.minestom.server.item", "Material", "MaterialImpl", "Materials");
        generator.generate(resource("entities.json"), "net.minestom.server.entity", "EntityType", "EntityTypeImpl", "EntityTypes");
        generator.generate(resource("potion_effects.json"), "net.minestom.server.potion", "PotionEffect", "PotionEffectImpl", "PotionEffects");
        generator.generate(resource("potions.json"), "net.minestom.server.potion", "PotionType", "PotionTypeImpl", "PotionTypes");
        generator.generate(resource("sounds.json"), "net.minestom.server.sound", "SoundEvent", "BuiltinSoundEvent", "SoundEvents");
        generator.generate(resource("custom_statistics.json"), "net.minestom.server.statistic", "StatisticType", "StatisticTypeImpl", "StatisticTypes");
        generator.generate(resource("attributes.json"), "net.minestom.server.entity.attribute", "Attribute", "AttributeImpl", "Attributes");
        generator.generate(resource("feature_flags.json"), "net.minestom.server", "FeatureFlag", "FeatureFlagImpl", "FeatureFlags");
        generator.generate(resource("villager_professions.json"), "net.minestom.server.entity", "VillagerProfession", "VillagerProfessionImpl", "VillagerProfessions");
        generator.generate(resource("game_events.json"), "net.minestom.server.game", "GameEvent", "GameEventImpl", "GameEvents");
        generator.generate(resource("block_sound_types.json"), "net.minestom.server.instance.block", "BlockSoundType", "BlockSoundImpl", "BlockSoundTypes");

        // Dynamic registries
        generator.generateKeys(resource("chat_types.json"), "net.minestom.server.message", "ChatType");
        generator.generateKeys(resource("dimension_types.json"), "net.minestom.server.world", "DimensionType");
        generator.generateKeys(resource("biomes.json"), "net.minestom.server.world.biome", "Biome");
        generator.generateKeys(resource("damage_types.json"), "net.minestom.server.entity.damage", "DamageType");
        generator.generateKeys(resource("trim_materials.json"), "net.minestom.server.item.armor", "TrimMaterial");
        generator.generateKeys(resource("trim_patterns.json"), "net.minestom.server.item.armor", "TrimPattern");
        generator.generateKeys(resource("banner_patterns.json"), "net.minestom.server.instance.block.banner", "BannerPattern");
        generator.generateKeys(resource("enchantments.json"), "net.minestom.server.item.enchant", "Enchantment");
        generator.generateKeys(resource("painting_variants.json"), "net.minestom.server.entity.metadata.other", "PaintingVariant");
        generator.generateKeys(resource("jukebox_songs.json"), "net.minestom.server.instance.block.jukebox", "JukeboxSong");
        generator.generateKeys(resource("instruments.json"), "net.minestom.server.item.instrument", "Instrument");
        generator.generateKeys(resource("wolf_variants.json"), "net.minestom.server.entity.metadata.animal.tameable", "WolfVariant");
        generator.generateKeys(resource("wolf_sound_variants.json"), "net.minestom.server.entity.metadata.animal.tameable", "WolfSoundVariant");
        generator.generateKeys(resource("cat_variants.json"), "net.minestom.server.entity.metadata.animal.tameable", "CatVariant");
        generator.generateKeys(resource("chicken_variants.json"), "net.minestom.server.entity.metadata.animal", "ChickenVariant");
        generator.generateKeys(resource("cow_variants.json"), "net.minestom.server.entity.metadata.animal", "CowVariant");
        generator.generateKeys(resource("frog_variants.json"), "net.minestom.server.entity.metadata.animal", "FrogVariant");
        generator.generateKeys(resource("pig_variants.json"), "net.minestom.server.entity.metadata.animal", "PigVariant");

        // Generate fluids
        new FluidGenerator(resource("fluids.json"), outputFolder).generate();

        LOGGER.info("Finished generating code");
    }

    private static InputStream resource(String name) {
        return Generators.class.getResourceAsStream("/" + name);
    }
}
