package me.justindevb.replay.chunk;

import java.io.ByteArrayOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class BlockEntityNbtReflectionSupport {

    private static final byte[] EMPTY_COMPOUND_NBT = new byte[] {0x0A, 0x00, 0x00, 0x00};
    private static final List<String> SNAPSHOT_METHOD_NAMES = List.of(
            "getSnapshotNBT",
            "getSnapshotNbt",
            "getSnapshotTag",
            "getSnapshot",
            "getTileEntityDataSnapshot",
            "getSnapshotCustomNbtOnly",
            "getTileEntitySnapshot");
    private static final List<String> NBT_IO_CLASS_NAMES = List.of(
            "net.minecraft.nbt.NbtIo",
            "net.minecraft.nbt.NBTCompressedStreamTools");

    private BlockEntityNbtReflectionSupport() {
    }

    static byte[] extractNamedCompoundBytes(Object blockState) {
        Object snapshotNbt = extractSnapshotNbt(blockState);
        if (snapshotNbt == null) {
            return emptyCompoundBytes();
        }

        byte[] serialized = serializeWithRuntimeNbtIo(snapshotNbt);
        if (serialized != null) {
            return serialized;
        }

        serialized = serializeWithInstanceWrite(snapshotNbt);
        return serialized != null ? serialized : emptyCompoundBytes();
    }

    static byte[] emptyCompoundBytes() {
        return EMPTY_COMPOUND_NBT.clone();
    }

    private static Object extractSnapshotNbt(Object blockState) {
        if (blockState == null) {
            return null;
        }

        for (Method method : orderedMethods(blockState.getClass())) {
            if (method.getParameterCount() != 0 || method.getReturnType() == Void.TYPE) {
                continue;
            }
            if (!isSnapshotMethodCandidate(method.getName())) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object result = method.invoke(blockState);
                if (result != null) {
                    return result;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return null;
    }

    private static byte[] serializeWithRuntimeNbtIo(Object snapshotNbt) {
        for (String className : NBT_IO_CLASS_NAMES) {
            try {
                Class<?> nbtIoClass = Class.forName(className, false, snapshotNbt.getClass().getClassLoader());
                for (Method method : nbtIoClass.getDeclaredMethods()) {
                    if (!Modifier.isStatic(method.getModifiers())) {
                        continue;
                    }
                    if (method.getParameterCount() != 2) {
                        continue;
                    }
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    if (!parameterTypes[0].isAssignableFrom(snapshotNbt.getClass())) {
                        continue;
                    }
                    if (!DataOutput.class.isAssignableFrom(parameterTypes[1])) {
                        continue;
                    }

                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try (DataOutputStream dataOut = new DataOutputStream(out)) {
                        method.setAccessible(true);
                        method.invoke(null, snapshotNbt, dataOut);
                        dataOut.flush();
                        return out.toByteArray();
                    }
                }
            } catch (ClassNotFoundException ignored) {
            } catch (IOException | IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
            }
        }
        return null;
    }

    private static byte[] serializeWithInstanceWrite(Object snapshotNbt) {
        for (Method method : orderedMethods(snapshotNbt.getClass())) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1) {
                continue;
            }
            if (!DataOutput.class.isAssignableFrom(method.getParameterTypes()[0])) {
                continue;
            }
            if (!isWriterMethodCandidate(method.getName())) {
                continue;
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DataOutputStream dataOut = new DataOutputStream(out)) {
                dataOut.writeByte(0x0A);
                dataOut.writeShort(0);
                method.setAccessible(true);
                method.invoke(snapshotNbt, dataOut);
                dataOut.flush();
                return out.toByteArray();
            } catch (IOException | IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
            }
        }
        return null;
    }

    private static List<Method> orderedMethods(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                methods.add(method);
            }
            current = current.getSuperclass();
        }
        methods.sort(Comparator.comparing(Method::getName));
        return methods;
    }

    private static boolean isSnapshotMethodCandidate(String methodName) {
        for (String candidate : SNAPSHOT_METHOD_NAMES) {
            if (candidate.equals(methodName)) {
                return true;
            }
        }
        String normalized = methodName.toLowerCase();
        return normalized.contains("snapshot") && (normalized.contains("nbt") || normalized.contains("tag"));
    }

    private static boolean isWriterMethodCandidate(String methodName) {
        String normalized = methodName.toLowerCase();
        return normalized.equals("a") || normalized.contains("write") || normalized.contains("save");
    }
}