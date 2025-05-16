package ru.giga.dev.helixanimation;

import blib.com.mojang.serialization.Codec;
import blib.com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.by1337.bc.CaseBlock;
import dev.by1337.bc.animation.AbstractAnimation;
import dev.by1337.bc.animation.AnimationContext;
import dev.by1337.bc.engine.MoveEngine;
import dev.by1337.bc.prize.Prize;
import dev.by1337.bc.prize.PrizeSelector;
import dev.by1337.bc.task.AsyncTask;
import dev.by1337.bc.yaml.CashedYamlContext;
import dev.by1337.virtualentity.api.entity.EntityEvent;
import dev.by1337.virtualentity.api.util.PlayerHashSet;
import dev.by1337.virtualentity.api.virtual.VirtualEntity;
import dev.by1337.virtualentity.api.virtual.item.VirtualItem;
import dev.by1337.virtualentity.api.virtual.projectile.VirtualFireworkRocketEntity;
import org.bukkit.*;
import org.bukkit.block.Lidded;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.by1337.blib.configuration.adapter.codec.YamlCodec;
import org.by1337.blib.configuration.serialization.BukkitCodecs;
import org.by1337.blib.configuration.serialization.DefaultCodecs;
import org.by1337.blib.geom.Vec3d;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

public class HelixAnimation extends AbstractAnimation {

    private final VirtualItem virtualItem = VirtualItem.create();
    private final Config config;
    private final Prize winner;

    public HelixAnimation(CaseBlock caseBlock, AnimationContext context, Runnable onEndCallback, PrizeSelector prizeSelector, CashedYamlContext yaml, Player player) {
        super(caseBlock, context, onEndCallback, prizeSelector, yaml, player);
        config = yaml.get("settings", v -> YamlCodec.codecOf(Config.CODEC).decode(v));
        winner = prizeSelector.getRandomPrize();
    }

    @Override
    protected void onStart() {
        caseBlock.hideHologram();
    }

    @Override
    protected void animate() throws InterruptedException {
        modifyAnchorCharges(charges -> config.anchorCharges.max - charges, 1, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE);
        modifyLidded(Lidded::open);

        setParamsPrize(prizeSelector.getRandomPrize());
        virtualItem.setPos(center);
        trackEntity(virtualItem);

        config.item.parabolaGroup.start.goTo(virtualItem, center).startSync(this);
        modifyLidded(Lidded::close);

        var taskSwap = new AsyncTask() {
            @Override
            public void run() {
                setParamsPrize(prizeSelector.getRandomPrize());
                config.item.swap.sound.ifPresent(v -> v.play(HelixAnimation.this));
            }
        }.timer().delay(config.item.swap.period).start(this);

        new AsyncTask() {
            final Vec3d startPos = virtualItem.getPos();
            double angle = Math.atan2(startPos.z - center.z, startPos.x - center.x);
            double radius = Math.sqrt(Math.pow(startPos.x - center.x, 2) + Math.pow(startPos.z - center.z, 2));
            double height = 0;

            @Override
            public void run() {
                double x = center.x + Math.cos(angle) * radius;
                double z = center.z + Math.sin(angle) * radius;

                virtualItem.setPos(new Vec3d(x, center.y + height, z));
                config.particles.ifPresent(v -> v.spawn(virtualItem.getPos(), HelixAnimation.this));

                angle += Math.PI / config.item.helix.stepAngle;
                height += config.item.helix.stepHeight;
                radius -= config.item.helix.fadeRadius;

                if (height > config.item.helix.maxHeight) cancel();
            }
        }.timer().delay(1).startSync(this);

        taskSwap.cancel();
        setParamsPrize(winner);
        modifyLidded(Lidded::open);

        config.item.parabolaGroup.end.goTo(virtualItem, center).startSync(this);

        for (Config.Firework firework : config.fireworks) {
            VirtualFireworkRocketEntity virtualFirework = VirtualFireworkRocketEntity.create();

            PlayerHashSet players = new PlayerHashSet();
            tracker.forEachViewers(players::add);

            FireworkEffect effect = FireworkEffect.builder()
                    .with(firework.type)
                    .withColor(firework.colors)
                    .build();

            ItemStack itemFirework = new ItemStack(Material.FIREWORK_ROCKET);
            FireworkMeta meta = (FireworkMeta) itemFirework.getItemMeta();
            meta.addEffect(effect);
            itemFirework.setItemMeta(meta);

            virtualFirework.setFireworkItem(itemFirework);
            virtualFirework.setPos(virtualItem.getPos().add(firework.offset));

            virtualFirework.tick(players);
            virtualFirework.sendEntityEvent(EntityEvent.FIREWORKS_EXPLODE);
            virtualFirework.tick(Set.of());
        }

        sleep(2000);
        removeEntity(virtualItem);
        modifyAnchorCharges(charges -> charges - config.anchorCharges.min, -1, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE);
        modifyLidded(Lidded::close);
    }

    private void modifyLidded(Consumer<Lidded> action) {
        sync(() -> {
            var state = blockPos.toBlock(world).getState();
            if (state instanceof Lidded lidded) {
                action.accept(lidded);
                state.update();
            }
        }).start();
    }

    private void modifyAnchorCharges(Function<Integer, Integer> steps, int direction, Sound sound) throws InterruptedException {
        var block = blockPos.toBlock(world);
        if (!(block.getBlockData() instanceof RespawnAnchor anchor)) return;

        for (int i = 0; i <= steps.apply(anchor.getCharges()) + 1; i++) {
            int newCharges = anchor.getCharges() + direction;
            if (newCharges < 0 || newCharges > 4) break;

            sync(() -> {
                anchor.setCharges(newCharges);
                block.setBlockData(anchor);
            }).start();
            playSound(sound, 1, 1);
            sleepTicks(config.anchorCharges.interval);
        }
    }

    private void setParamsPrize(Prize prize) {
        virtualItem.setItem(prize.itemStack());
        virtualItem.setCustomNameVisible(true);
        virtualItem.setCustomName(prize.displayNameComponent());
        virtualItem.setNoGravity(true);
        virtualItem.setMotion(Vec3d.ZERO);
    }

    @Override
    protected void onEnd() {
        caseBlock.showHologram();
        if (winner != null) caseBlock.givePrize(winner, player);
    }

    @Override
    protected void onClick(VirtualEntity virtualEntity, Player player) {
    }

    @Override
    public void onInteract(PlayerInteractEvent playerInteractEvent) {
    }

    public record Config(Optional<Particles> particles, AnchorCharges anchorCharges, Item item,
                         List<Firework> fireworks) {
        public final static Codec<Config> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Particles.CODEC.optionalFieldOf("particles").forGetter(Config::particles),
                AnchorCharges.CODEC.optionalFieldOf("anchor_charges", AnchorCharges.DEFAULT).forGetter(Config::anchorCharges),
                Item.CODEC.fieldOf("item").forGetter(Config::item),
                Firework.CODEC.listOf().optionalFieldOf("fireworks", Collections.emptyList()).forGetter(Config::fireworks)
        ).apply(instance, Config::new));

        public record AnchorCharges(long interval, int max, int min) {
            public final static AnchorCharges DEFAULT = new AnchorCharges(15, 3, 1);
            public final static Codec<AnchorCharges> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.LONG.optionalFieldOf("interval", 15L).forGetter(AnchorCharges::interval),
                    Codec.INT.optionalFieldOf("max", 3).forGetter(AnchorCharges::max),
                    Codec.INT.optionalFieldOf("min", 1).forGetter(AnchorCharges::min)
            ).apply(instance, AnchorCharges::new));

            public AnchorCharges {
                max = Math.min(4, Math.max(0, max));
                min = Math.min(max, Math.max(0, min));
            }
        }

        public record Particles(Particle particle, Vec3d offset, int count, double speed) {
            public final static Codec<Particle> PARTICLE = DefaultCodecs.createAnyEnumCodec(Particle.class);
            public final static Codec<Particles> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    PARTICLE.fieldOf("name").forGetter(Particles::particle),
                    Vec3d.CODEC.fieldOf("offset").forGetter(Particles::offset),
                    Codec.INT.optionalFieldOf("count", 0).forGetter(Particles::count),
                    Codec.DOUBLE.optionalFieldOf("speed", 0.1).forGetter(Particles::speed)
            ).apply(instance, Particles::new));

            public void spawn(Vec3d pos, AbstractAnimation animation) {
                animation.spawnParticle(particle, pos, count, offset.x, offset.y, offset.z, speed);
            }
        }

        public record Item(Parabola.ParabolaGroup parabolaGroup, Helix helix, Swap swap) {
            public final static Codec<Item> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Parabola.ParabolaGroup.CODEC.fieldOf("parabola").forGetter(Item::parabolaGroup),
                    Helix.CODEC.fieldOf("helix").forGetter(Item::helix),
                    Swap.CODEC.fieldOf("swap").forGetter(Item::swap)
            ).apply(instance, Item::new));

            public record Parabola(Vec3d offset, double speed, double height) {
                public final static Codec<Parabola> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Vec3d.CODEC.fieldOf("offset").forGetter(Parabola::offset),
                        Codec.DOUBLE.fieldOf("speed").forGetter(Parabola::speed),
                        Codec.DOUBLE.fieldOf("height").forGetter(Parabola::height)
                ).apply(instance, Parabola::new));

                private record ParabolaGroup(Parabola start, Parabola end) {
                    public final static Codec<ParabolaGroup> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                            Parabola.CODEC.fieldOf("start").forGetter(ParabolaGroup::start),
                            Parabola.CODEC.fieldOf("end").forGetter(ParabolaGroup::end)
                    ).apply(instance, ParabolaGroup::new));
                }

                public AsyncTask goTo(VirtualItem virtualItem, Vec3d center) {
                    return MoveEngine.goToParabola(virtualItem, center.add(offset), speed, height);
                }
            }

            public record Helix(double stepAngle, double stepHeight, double fadeRadius, double maxHeight) {
                public final static Codec<Helix> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.DOUBLE.fieldOf("step_angle").forGetter(Helix::stepAngle),
                        Codec.DOUBLE.fieldOf("step_height").forGetter(Helix::stepHeight),
                        Codec.DOUBLE.fieldOf("fade_radius").forGetter(Helix::fadeRadius),
                        Codec.DOUBLE.fieldOf("max_height").forGetter(Helix::maxHeight)
                ).apply(instance, Helix::new));
            }

            public record Swap(long period, Optional<Sound> sound) {
                public final static Codec<Swap> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.LONG.fieldOf("period").forGetter(Swap::period),
                        Sound.CODEC.optionalFieldOf("sound").forGetter(Swap::sound)
                ).apply(instance, Swap::new));

                public record Sound(org.bukkit.Sound bukkit, float volume, float pitch) {
                    public final static Codec<Sound> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                            SoundFixer.CODEC.fieldOf("name").forGetter(Sound::bukkit),
                            Codec.FLOAT.optionalFieldOf("volume", 1f).forGetter(Sound::volume),
                            Codec.FLOAT.optionalFieldOf("pitch", 1f).forGetter(Sound::pitch)
                    ).apply(instance, Sound::new));

                    public void play(AbstractAnimation animation) {
                        animation.playSound(bukkit, volume, pitch);
                    }
                }
            }
        }

        public record Firework(FireworkEffect.Type type, List<Color> colors, Vec3d offset) {
            public static final Codec<FireworkEffect.Type> FIREWORK_TYPE = DefaultCodecs.createAnyEnumCodec(FireworkEffect.Type.class);
            public final static Codec<Firework> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    FIREWORK_TYPE.optionalFieldOf("type", FireworkEffect.Type.BALL).forGetter(Firework::type),
                    BukkitCodecs.COLOR.listOf().fieldOf("colors").forGetter(Firework::colors),
                    Vec3d.CODEC.fieldOf("offset").forGetter(Firework::offset)
            ).apply(instance, Firework::new));
        }
    }
}