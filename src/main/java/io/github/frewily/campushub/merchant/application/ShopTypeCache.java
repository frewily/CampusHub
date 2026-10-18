package io.github.frewily.campushub.merchant.application;

import io.github.frewily.campushub.entity.ShopType;

import java.util.List;

public interface ShopTypeCache {
    Lookup read();

    void write(List<ShopType> types);

    /** Cache presence is distinct from an empty (or decoder-null) value. */
    final class Lookup {
        private final boolean hit;
        private final List<ShopType> items;

        private Lookup(boolean hit, List<ShopType> items) {
            this.hit = hit;
            this.items = items;
        }

        public static Lookup miss() { return new Lookup(false, null); }

        public static Lookup hit(List<ShopType> items) { return new Lookup(true, items); }

        public boolean isHit() { return hit; }

        public List<ShopType> getItems() { return items; }
    }
}
