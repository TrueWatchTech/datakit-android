package com.ft.sdk;

/**
 * Source of an automatically tracked click or application launch.
 */
public enum ActionSourceType {
    /** Click on a View. */
    CLICK_VIEW,
    /** Click on a ListView or GridView item. */
    CLICK_LIST_ITEM,
    /** Click on a child of an expandable list group. */
    CLICK_EXPAND_LIST_ITEM,
    /** Click on an expandable list group. */
    CLICK_EXPAND_GROUP_ITEM,
    /** Selection of a radio button. */
    CLICK_RADIO_BUTTON,
    /** Selection of a tab. */
    CLICK_TAB,
    /** Click on a dialog button. */
    CLICK_DIALOG_BUTTON,
    /** Selection of a menu item. */
    CLICK_MENU_ITEM,
    /** Release of the Android back key. */
    CLICK_BACK,
    /** Release of the Android menu key. */
    CLICK_MENU,
    /** Release of the D-pad center key when the window has no focused View. */
    CLICK_PAD_CENTER,
    /** Foreground return that reuses an existing Activity. */
    LAUNCH_HOT,
    /** Initial application launch classified as a cold start. */
    LAUNCH_COLD,
    /** Activity creation to its first draw in an existing or already initialized process. */
    LAUNCH_WARM,
}
