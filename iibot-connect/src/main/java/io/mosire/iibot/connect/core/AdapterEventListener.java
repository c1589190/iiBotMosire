package io.mosire.iibot.connect.core;

/** 适配器可观测事件监听器。 */
@FunctionalInterface
public interface AdapterEventListener {
    void onEvent(AdapterEvent event);
}
