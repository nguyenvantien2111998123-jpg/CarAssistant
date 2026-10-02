package com.carassistant.v10;

/**
 * Display identity used by Car Assistant V10.
 *
 * Keep these values synchronized with:
 * 1. V10ProjectionSession.createVirtualDisplay()
 * 2. InputHooks
 * 3. any future Gearhead hooks
 */
public final class V10Display {

    public static final String PACKAGE = "com.carassistant.v10";
    public static final String PREFIX = "Car Assistant V10 ";

    private V10Display() {
    }

    public static String nameFor(int sessionIndex) {
        return PREFIX + (sessionIndex + 1);
    }

    public static boolean matches(
            String ownerPackage,
            String name,
            int type,
            int ownerUid) {

        if (!PACKAGE.equals(ownerPackage)
                || type != 5
                || ownerUid < 10000) {
            return false;
        }

        return (PREFIX + "1").equals(name)
                || (PREFIX + "2").equals(name)
                || (PREFIX + "3").equals(name);
    }
}
