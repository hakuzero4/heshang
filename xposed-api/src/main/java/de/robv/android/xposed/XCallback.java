package de.robv.android.xposed;

public abstract class XCallback {
    public int priority;

    public XCallback() {
    }

    public XCallback(int priority) {
        this.priority = priority;
    }

    public static abstract class Param {
    }
}
