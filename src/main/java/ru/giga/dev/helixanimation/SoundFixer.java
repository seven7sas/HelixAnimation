package ru.giga.dev.helixanimation;

import blib.com.mojang.serialization.Codec;
import org.bukkit.Sound;
import org.by1337.blib.text.MessageFormatter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class SoundFixer {

    public static final Codec<Sound> CODEC = Codec.STRING.xmap(SoundFixer::byName, v -> v.getKey().getKey().replace(".", "_"));
    private static final Map<String, Sound> LOOKUP_BY_NAME;

    public static Sound byName(String name) {
        return Objects.requireNonNull(LOOKUP_BY_NAME.get(name.toLowerCase(Locale.ENGLISH)), MessageFormatter.apply("Sound by name {} not found", name));
    }

    static {
        LOOKUP_BY_NAME = new HashMap<>();
        if (Sound.class.isEnum()) {
            for (Sound value : Sound.values()) {
                LOOKUP_BY_NAME.put(value.name().toLowerCase(Locale.ENGLISH), value);
            }
        } else {
            try { // in versions >=1.21.3 Sound is no longer enum
                for (Field field : Sound.class.getFields()) {
                    field.setAccessible(true);

                    if (!Modifier.isStatic(field.getModifiers())) continue;
                    if (field.getType() != Sound.class) continue;

                    Sound sound = (Sound) field.get(null);
                    LOOKUP_BY_NAME.put(field.getName().toLowerCase(Locale.ENGLISH), sound);
                }
            } catch (Throwable t) {
                throw new ExceptionInInitializerError(t);
            }
        }
    }
}
