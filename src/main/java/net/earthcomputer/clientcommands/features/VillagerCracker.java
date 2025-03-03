package net.earthcomputer.clientcommands.features;

import net.earthcomputer.clientcommands.command.ClientCommandHelper;
import net.earthcomputer.clientcommands.command.VillagerCommand;
import net.earthcomputer.clientcommands.interfaces.IVillager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddExperienceOrbPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.UUID;

public class VillagerCracker {
    // This value was computed by brute forcing all seeds
    public static final float MAX_ERROR = 5 * 0x1.0p-24f;

    @Nullable
    private static UUID villagerUuid = null;
    @Nullable
    private static WeakReference<Villager> cachedVillager = null;
    @Nullable
    private static GlobalPos clockPos = null;
    @Nullable
    public static VillagerCommand.Offer targetOffer = null;
    @Nullable
    private static Long lastServerTick = null;
    private static boolean receivedClockRateWarning = false;

    @Nullable
    public static Villager getVillager() {
        if (villagerUuid == null) {
            cachedVillager = null;
            return null;
        }
        if (cachedVillager != null) {
            Villager villager = cachedVillager.get();
            if (villager != null && !villager.isRemoved()) {
                return villager;
            }
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            for (Entity entity : level.entitiesForRendering()) {
                if (entity.getUUID() == villagerUuid && entity instanceof Villager villager) {
                    cachedVillager = new WeakReference<>(villager);
                    return villager;
                }
            }
        }
        return null;
    }

    @Nullable
    public static GlobalPos getClockPos() {
        return clockPos;
    }

    public static void setTargetVillager(@Nullable Villager villager) {
        Villager oldVillager = getVillager();
        if (oldVillager != null) {
            ((IVillager) oldVillager).clientcommands_getVillagerRngSimulator().reset();
        }

        if (clockPos == null) {
            ClientCommandHelper.sendHelp(Component.translatable("commands.cvillager.help.noClock"));
        }

        ClientLevel level = Minecraft.getInstance().level;

        if (level.getDayTime() % 24000 < 12000) {
            ClientCommandHelper.sendHelp(Component.translatable("commands.cvillager.help.day"));
        }

        VillagerCracker.cachedVillager = new WeakReference<>(villager);
        VillagerCracker.villagerUuid = villager == null ? null : villager.getUUID();
    }

    public static void setClockPos(@Nullable GlobalPos pos) {
        VillagerCracker.clockPos = pos;
    }

    public static void onSoundEventPlayed(ClientboundSoundPacket packet) {
        Villager targetVillager = getVillager();
        if (targetVillager == null || getClockPos() == null) {
            return;
        }

        switch (packet.getSound().value().getLocation().toString()) {
            case "minecraft:entity.villager.ambient", "minecraft:entity.villager.trade" -> ((IVillager) targetVillager).clientcommands_onAmbientSoundPlayed(packet.getPitch());
            case "minecraft:entity.villager.no" -> ((IVillager) targetVillager).clientcommands_onNoSoundPlayed(packet.getPitch());
            case "minecraft:entity.villager.yes" -> ((IVillager) targetVillager).clientcommands_onYesSoundPlayed(packet.getPitch());
            case "minecraft:entity.generic.splash" -> ((IVillager) targetVillager).clientcommands_onSplashSoundPlayed(packet.getPitch());
        }
    }

    public static void onXpOrbSpawned(ClientboundAddExperienceOrbPacket packet) {
        Villager targetVillager = getVillager();
        if (targetVillager == null) {
            return;
        }

        ((IVillager) targetVillager).clientcommands_onXpOrbSpawned(packet.getValue());
    }

    public static void onServerTick() {
        long now = System.currentTimeMillis();

        Villager targetVillager = getVillager();
        if (targetVillager == null) {
            lastServerTick = now;
            return;
        }

        if (lastServerTick != null && now - lastServerTick > 80L && !receivedClockRateWarning) {
            ClientCommandHelper.sendHelp(Component.translatable("commands.cvillager.help.tooSlow"));
            receivedClockRateWarning = true;
        }

        ((IVillager) targetVillager).clientcommands_onServerTick();

        lastServerTick = now;
    }
}
