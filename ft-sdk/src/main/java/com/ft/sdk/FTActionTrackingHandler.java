package com.ft.sdk;

/**
 * Action tracking handler interface
 * <p>
 * This interface allows you to customize how user actions are tracked in RUM data.
 * When a user action occurs (click, touch, etc.), this handler will be called to determine
 * how the action should be tracked.
 * <p>
 * Automatic startup events use {@link ActionSourceType#LAUNCH_COLD},
 * {@link ActionSourceType#LAUNCH_WARM} or {@link ActionSourceType#LAUNCH_HOT}.
 * Warm starts measure Activity creation to the first draw; hot starts measure reuse of an
 * existing Activity from Start to post-Resume. Returning null also filters these launch events.
 *
 * @see FTRUMConfig#setActionTrackingHandler(FTActionTrackingHandler)
 * @see HandlerAction
 * @see ActionEventWrapper
 * @see ActionSourceType
 */
public interface FTActionTrackingHandler {

    /**
     * Determine how a user action should be tracked
     * <p>
     * This method is called for user actions and automatic cold, warm and hot launches.
     * You can customize the action name and add custom properties for the action.
     *
     * @param actionEventWrapper The action event wrapper containing information about the action
     * @return HandlerAction with custom action name and properties, or null to skip tracking
     * @see HandlerAction
     * @see ActionEventWrapper#getSourceType()
     * @see ActionEventWrapper#getSource()
     * @see ActionEventWrapper#getExtra()
     */
    HandlerAction resolveHandlerAction(ActionEventWrapper actionEventWrapper);
}
