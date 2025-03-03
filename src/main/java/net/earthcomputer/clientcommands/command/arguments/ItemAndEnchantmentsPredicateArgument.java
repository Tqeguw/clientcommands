package net.earthcomputer.clientcommands.command.arguments;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.earthcomputer.clientcommands.command.VillagerCommand;
import net.earthcomputer.clientcommands.util.MultiVersionCompat;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ItemAndEnchantmentsPredicateArgument implements ArgumentType<ItemAndEnchantmentsPredicateArgument.ItemAndEnchantmentsPredicate> {

    private static final Collection<String> EXAMPLES = Arrays.asList("stick with sharpness 4 without sweeping *", "minecraft:diamond_sword with sharpness *");

    private static final SimpleCommandExceptionType INCOMPATIBLE_ENCHANTMENT_EXCEPTION = new SimpleCommandExceptionType(Component.translatable("commands.cenchant.incompatible"));
    private static final DynamicCommandExceptionType ID_INVALID_EXCEPTION = new DynamicCommandExceptionType(id -> Component.translatable("argument.item.id.invalid", id));

    private final HolderLookup<Enchantment> enchantmentLookup;
    private Predicate<Item> itemPredicate = item -> true;
    private BiPredicate<Item, Holder<Enchantment>> enchantmentPredicate = (item, ench) -> true;
    private boolean constrainMaxLevel = false;

    private ItemAndEnchantmentsPredicateArgument(HolderLookup.Provider holderLookupProvider) {
        this.enchantmentLookup = holderLookupProvider.lookupOrThrow(Registries.ENCHANTMENT);
    }

    public static ItemAndEnchantmentsPredicateArgument itemAndEnchantmentsPredicate(HolderLookup.Provider holderLookupProvider) {
        return new ItemAndEnchantmentsPredicateArgument(holderLookupProvider);
    }

    public ItemAndEnchantmentsPredicateArgument withItemPredicate(Predicate<Item> predicate) {
        this.itemPredicate = predicate;
        return this;
    }

    public ItemAndEnchantmentsPredicateArgument withEnchantmentPredicate(BiPredicate<Item, Holder<Enchantment>> predicate) {
        this.enchantmentPredicate = predicate;
        return this;
    }

    public ItemAndEnchantmentsPredicateArgument constrainMaxLevel() {
        this.constrainMaxLevel = true;
        return this;
    }

    public static ItemAndEnchantmentsPredicate getItemAndEnchantmentsPredicate(CommandContext<?> context, String name) {
        return context.getArgument(name, ItemAndEnchantmentsPredicate.class);
    }

    @Override
    public ItemAndEnchantmentsPredicate parse(StringReader reader) throws CommandSyntaxException {
        Parser parser = new Parser(reader);
        parser.parse();

        Predicate<List<EnchantmentInstance>> predicate = enchantments -> {
            if (parser.exact && (parser.with.size() != enchantments.size())) {
                return false;
            }
            if (parser.ordered) {
                int enchIndex = 0;
                for (EnchantmentInstancePredicate with : parser.with) {
                    while (enchIndex < enchantments.size() && !with.test(enchantments.get(enchIndex))) {
                        enchIndex++;
                    }
                    if (enchIndex >= enchantments.size()) {
                        return false;
                    }
                    // we're matching, increment index
                    enchIndex++;
                }
            } else {
                for (EnchantmentInstancePredicate with : parser.with) {
                    boolean found = false;
                    for (EnchantmentInstance ench : enchantments) {
                        if (with.test(ench)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        return false;
                    }
                }
            }
            if (parser.exact) {
                return true;
            }
            for (EnchantmentInstancePredicate without : parser.without) {
                for (EnchantmentInstance ench : enchantments) {
                    if (without.test(ench)) {
                        return false;
                    }
                }
            }
            return true;
        };

        return new ItemAndEnchantmentsPredicate(parser.item, predicate, parser.with, parser.without, parser.exact, parser.ordered);
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        StringReader reader = new StringReader(builder.getInput());
        reader.setCursor(builder.getStart());

        Parser parser = new Parser(reader);
        try {
            parser.parse();
        } catch (CommandSyntaxException ignore) {
        }

        if (parser.suggestor != null) {
            parser.suggestor.accept(builder);
        }

        return builder.buildFuture();
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }

    public record ItemAndEnchantmentsPredicate(Item item, Predicate<List<EnchantmentInstance>> predicate, List<EnchantmentInstancePredicate> with, List<EnchantmentInstancePredicate> without, boolean exact, boolean ordered) implements Predicate<ItemStack> {
        @Override
        public boolean test(ItemStack stack) {
            if (item != stack.getItem() && (item != Items.BOOK || stack.getItem() != Items.ENCHANTED_BOOK)) {
                return false;
            }
            List<EnchantmentInstance> enchantments = Stream.concat(stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).entrySet().stream(), stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY).entrySet().stream())
                .map(entry -> new EnchantmentInstance(entry.getKey(), entry.getIntValue()))
                .toList();
            return predicate.test(enchantments);
        }

        @Override
        public String toString() {
            String itemName = item.getName(new ItemStack(item)).getString();

            StringBuilder flagsBuilder = new StringBuilder();
            if (exact) {
                flagsBuilder.append("Exactly");
            }
            if (ordered) {
                flagsBuilder.append("Ordered");
            }
            String flags = flagsBuilder.isEmpty() ? "" : " [" + flagsBuilder + ')';

            String enchantments = Stream.concat(
                with.stream().filter(enchantment -> enchantment.enchantment().unwrapKey().isPresent()).map(EnchantmentInstancePredicate::toString),
                without.stream().filter(enchantment -> enchantment.enchantment().unwrapKey().isPresent()).map(enchantment -> "!" + enchantment)
            ).collect(Collectors.joining(", "));
            if (!enchantments.isEmpty()) {
                enchantments = '(' + enchantments + ')';
            }

            return String.format("%s%s %s", itemName, flags, enchantments);
        }
    }

    private class Parser {
        private final StringReader reader;
        private Consumer<SuggestionsBuilder> suggestor;

        private Item item;
        private final List<EnchantmentInstancePredicate> with = new ArrayList<>();
        private final List<EnchantmentInstancePredicate> without = new ArrayList<>();

        private boolean exact = false;
        private boolean ordered = false;

        public Parser(StringReader reader) {
            this.reader = reader;
        }

        public void parse() throws CommandSyntaxException {
            this.item = parseItem();

            while (reader.canRead()) {
                parseSpace();
                if (!parseEnchantmentInstancePredicate()) {
                    break;
                }
            }
        }

        private Item parseItem() throws CommandSyntaxException {
            suggestEnchantableItem();
            int start = reader.getCursor();
            ResourceLocation identifier = ResourceLocation.read(reader);
            Item item = BuiltInRegistries.ITEM.getOptional(identifier).orElseThrow(() -> {
                reader.setCursor(start);
                return ID_INVALID_EXCEPTION.createWithContext(reader, identifier);
            });
            if ((item.getEnchantmentValue() <= 0 || !itemPredicate.test(item)) && (item != Items.ENCHANTED_BOOK || !itemPredicate.test(Items.BOOK))) {
                reader.setCursor(start);
                throw INCOMPATIBLE_ENCHANTMENT_EXCEPTION.createWithContext(reader);
            }
            if (item == Items.ENCHANTED_BOOK) {
                item = Items.BOOK;
            }
            return item;
        }

        private boolean parseEnchantmentInstancePredicate() throws CommandSyntaxException {
            ItemStack stack = new ItemStack(item);

            int start = reader.getCursor();
            Option option = parseOption();
            if (option == null) {
                reader.setCursor(start);
                return false;
            }

            boolean suggest = reader.canRead();
            if (option == Option.EXACT) {
                exact = true;
                return true;
            }
            if (option == Option.ORDERED) {
                ordered = true;
                return true;
            }

            if (exact || ordered) {
                reader.setCursor(start);
                return false;
            }

            parseSpace();

            Holder<Enchantment> enchantment = parseEnchantment(suggest, option, stack);
            suggest = reader.canRead();
            parseSpace();
            MinMaxBounds.Ints level = parseEnchantmentLevel(suggest, option, stack, enchantment);

            if (option == Option.WITH) {
                with.add(new EnchantmentInstancePredicate(enchantment, level));
            } else {
                without.add(new EnchantmentInstancePredicate(enchantment, level));
            }

            return true;
        }

        private enum Option {
            WITH,
            WITHOUT,
            EXACT,
            ORDERED,
        }

        @Nullable
        private Option parseOption() {
            suggestOption();
            String option = reader.readUnquotedString();
            return switch (option) {
                case "with" -> Option.WITH;
                case "without" -> Option.WITHOUT;
                case "exactly" -> exact ? null : Option.EXACT;
                case "ordered" -> ordered || MultiVersionCompat.INSTANCE.getProtocolVersion() >= MultiVersionCompat.V1_21 ? null : Option.ORDERED;
                default -> null;
            };
        }

        private Holder<Enchantment> parseEnchantment(boolean suggest, Option option, ItemStack stack) throws CommandSyntaxException {
            List<Holder.Reference<Enchantment>> allowedEnchantments = new ArrayList<>();
            enchantmentLookup.listElements().forEach(ench -> {
                boolean skip = (!ench.value().canEnchant(stack) && stack.getItem() != Items.BOOK) || !enchantmentPredicate.test(stack.getItem(), ench);
                if (skip) {
                    return;
                }
                if (option == Option.WITH) {
                    for (EnchantmentInstancePredicate ench2 : with) {
                        if (!Enchantment.areCompatible(ench, ench2.enchantment)) {
                            return;
                        }
                    }
                    for (EnchantmentInstancePredicate ench2 : without) {
                        if (ench2.enchantment == ench && ench2.level.isAny()) {
                            return;
                        }
                    }
                } else {
                    for (EnchantmentInstancePredicate ench2 : with) {
                        if (ench2.enchantment == ench && ench2.level.isAny()) {
                            return;
                        }
                    }
                    for (EnchantmentInstancePredicate ench2 : without) {
                        if (ench2.enchantment == ench && ench2.level.isAny()) {
                            return;
                        }
                    }
                }
                allowedEnchantments.add(ench);
            });

            int start = reader.getCursor();
            if (suggest) {
                suggestor = suggestions -> {
                    SuggestionsBuilder builder = suggestions.createOffset(start);
                    SharedSuggestionProvider.suggestResource(allowedEnchantments.stream().map(ench -> ench.key().location()), builder);
                    suggestions.add(builder);
                };
            }

            ResourceLocation identifier = ResourceLocation.read(reader);
            ResourceKey<Enchantment> enchantmentKey = ResourceKey.create(Registries.ENCHANTMENT, identifier);
            Holder<Enchantment> enchantment = enchantmentLookup.get(enchantmentKey).orElseThrow(() -> {
                reader.setCursor(start);
                return ResourceArgument.ERROR_UNKNOWN_RESOURCE.createWithContext(reader, identifier, Registries.ENCHANTMENT.location());
            });

            if (!enchantment.value().canEnchant(stack) && stack.getItem() != Items.BOOK) {
                reader.setCursor(start);
                throw INCOMPATIBLE_ENCHANTMENT_EXCEPTION.createWithContext(reader);
            }

            return enchantment;
        }

        private MinMaxBounds.Ints parseEnchantmentLevel(boolean suggest, Option option, ItemStack stack, Holder<Enchantment> enchantment) throws CommandSyntaxException {
            int maxLevel;
            if (constrainMaxLevel) {
                int enchantability = stack.getItem().getEnchantmentValue();
                int level = 30 + 1 + enchantability / 4 + enchantability / 4;
                level += Math.round(level * 0.15f);
                for (maxLevel = enchantment.value().getMaxLevel(); maxLevel >= 1; maxLevel--) {
                    if (level >= enchantment.value().getMinCost(maxLevel)) {
                        break;
                    }
                }
            } else {
                maxLevel = enchantment.value().getMaxLevel();
            }

            List<Integer> allowedLevels = new ArrayList<>();
            nextLevel: for (int level = -1; level <= maxLevel; level++) {
                if (level == 0) {
                    continue;
                }

                if (option == Option.WITH) {
                    for (EnchantmentInstancePredicate ench : without) {
                        if (ench.enchantment == enchantment && (level == -1 || ench.level.matches(level))) {
                            continue nextLevel;
                        }
                    }
                } else {
                    for (EnchantmentInstancePredicate ench : with) {
                        if (ench.enchantment == enchantment && (level == -1 || ench.level.matches(level))) {
                            continue nextLevel;
                        }
                    }
                    for (EnchantmentInstancePredicate ench : without) {
                        if (ench.enchantment == enchantment && (level == -1 || ench.level.matches(level))) {
                            continue nextLevel;
                        }
                    }
                }
                allowedLevels.add(level);
            }

            int start = reader.getCursor();

            if (suggest) {
                suggestor = suggestions -> {
                    SuggestionsBuilder builder = suggestions.createOffset(start);
                    for (int allowed : allowedLevels) {
                        if (allowed == -1) {
                            builder.suggest("*");
                        }
                        else {
                            builder.suggest(allowed);
                        }
                    }
                    suggestions.add(builder);
                };
            }

            if (!reader.canRead()) {
                throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerExpectedInt().createWithContext(reader);
            }

            if (reader.peek() == '*') {
                reader.skip();
                return MinMaxBounds.Ints.ANY;
            }

            int levelStart = reader.getCursor();
            MinMaxBounds.Ints result = MinMaxBounds.Ints.fromReader(reader);
            if (allowedLevels.stream().noneMatch(result::matches)) {
                int levelEnd = reader.getCursor();
                reader.setCursor(levelStart);
                throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerInvalidInt().createWithContext(reader, reader.getString().substring(levelStart, levelEnd));
            }
            return result;
        }

        private void parseSpace() throws CommandSyntaxException {
            if (reader.canRead()) {
                if (reader.peek() != CommandDispatcher.ARGUMENT_SEPARATOR_CHAR) {
                    throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherExpectedArgumentSeparator().createWithContext(reader);
                }
                reader.skip();
            }
        }

        private void suggestEnchantableItem() {
            List<ResourceLocation> allowed = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                if (item.getEnchantmentValue() > 0 && itemPredicate.test(item)) {
                    if (MultiVersionCompat.INSTANCE.doesItemExist(item)) {
                        allowed.add(BuiltInRegistries.ITEM.getKey(item));
                    }
                } else if (item == Items.ENCHANTED_BOOK && itemPredicate.test(Items.BOOK)) {
                    if (MultiVersionCompat.INSTANCE.doesItemExist(item)) {
                        allowed.add(BuiltInRegistries.ITEM.getKey(Items.ENCHANTED_BOOK));
                    }
                }
            }
            int start = reader.getCursor();
            suggestor = suggestions -> {
                SuggestionsBuilder builder = suggestions.createOffset(start);
                SharedSuggestionProvider.suggestResource(allowed, builder);
                suggestions.add(builder);
            };
        }

        private void suggestOption() {
            int start = reader.getCursor();
            suggestor = suggestions -> {
                SuggestionsBuilder builder = suggestions.createOffset(start);
                List<String> validOptions = new ArrayList<>(4);
                if (!exact && !ordered) {
                    Collections.addAll(validOptions, "with", "without");
                }
                if (!exact) {
                    validOptions.add("exactly");
                }
                if (!ordered && MultiVersionCompat.INSTANCE.getProtocolVersion() < MultiVersionCompat.V1_21) {
                    validOptions.add("ordered");
                }
                SharedSuggestionProvider.suggest(validOptions, builder);
                suggestions.add(builder);
            };
        }
    }

    public record EnchantmentInstancePredicate(Holder<Enchantment> enchantment, MinMaxBounds.Ints level) implements Predicate<EnchantmentInstance> {
        @Override
        public boolean test(EnchantmentInstance enchInstance) {
            return enchantment.equals(enchInstance.enchantment) && level.matches(enchInstance.level);
        }

        @Override
        public String toString() {
            return Component.translatable(enchantment.unwrapKey().get().location().toLanguageKey("enchantment")).getString() + " " + Objects.requireNonNullElse(VillagerCommand.displayRange(enchantment.value().getMaxLevel(), level), "*");
        }
    }
}
