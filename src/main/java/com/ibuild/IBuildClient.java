for (int z = c[o + 2]; z <= c[o + 5]; z++) {
                    int runStart = 0, runIdx = -1;
                    for (int x = c[o]; x <= c[o + 3]; x++) {
                        BlockState st = level.getBlockState(mp.set(x, y, z));
                        Integer boxed = idx.get(st);
                        if (boxed == null) {
                            boxed = pal.size();
                            pal.add(ser(st));
                            idx.put(st, boxed);
                        }
                        int pi = boxed;
                        if (pi == runIdx) continue;
                        if (runIdx >= 0) runs.add7(runStart, y, z, x - 1, y, z, runIdx);
                        runStart = x;
                        runIdx = pi;
                    }
                    runs.add7(runStart, y, z, c[o + 3], y, z, runIdx);
                    if (runs.size / 7 > UNDO_MAX_RUNS) return null;
                }
            }
        }
        return new Job("undo", runs.trimmed(), runs.size / 7, pal.toArray(new String[0]));
    }

    /** BlockState -> chaine utilisable dans /setblock et /fill, ex. minecraft:oak_stairs[facing=north,half=bottom]. */
    private static String ser(BlockState st) {
        StringBuilder sb = new StringBuilder(BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
        Collection<Property<?>> props = st.getProperties();
        if (!props.isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (Property<?> p : props) {
                if (!first) sb.append(',');
                first = false;
                sb.append(p.getName()).append('=').append(valueName(st, p));
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private static <T extends Comparable<T>> String valueName(BlockState st, Property<T> p) {
        return p.getName(st.getValue(p));
    }

    // ---------- hauteur du monde (sans dependre d'un nom d'API) ----------

    private static int minY(ClientLevel l) {
        int lo = -(1 << 16), hi = 0; // lo : hors du monde, hi : dans le monde
        while (hi - lo > 1) {
            int mid = (lo + hi) >> 1;
            if (l.isOutsideBuildHeight(new BlockPos(0, mid, 0))) lo = mid; else hi = mid;
        }
        return hi;
    }

    private static int maxY(ClientLevel l) {
        int lo = 0, hi = 1 << 16; // lo : dans le monde, hi : hors du monde
        while (hi - lo > 1) {
            int mid = (lo + hi) >> 1;
            if (l.isOutsideBuildHeight(new BlockPos(0, mid, 0))) hi = mid; else lo = mid;
        }
        return lo;
    }
}
