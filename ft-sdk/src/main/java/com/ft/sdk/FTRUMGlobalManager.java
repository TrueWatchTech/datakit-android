package com.ft.sdk;


import com.ft.sdk.garble.bean.AppState;
import com.ft.sdk.garble.bean.ErrorType;
import com.ft.sdk.garble.bean.NetStatusBean;
import com.ft.sdk.garble.bean.ResourceParams;
import com.ft.sdk.garble.bean.ViewTrackingType;
import com.ft.sdk.garble.utils.Utils;

import java.util.HashMap;

/** Manually reports RUM events after {@link FTSdk#initRUMWithConfig(FTRUMConfig)} enables collection. */
public class FTRUMGlobalManager {

    private static class SingletonHolder {
        private static final FTRUMGlobalManager INSTANCE = new FTRUMGlobalManager();
    }

    /**
     * Returns the singleton used to manually report RUM view, action, resource, error, and long task data.
     */
    public static FTRUMGlobalManager get() {
        return FTRUMGlobalManager.SingletonHolder.INSTANCE;
    }

    private FTRUMInnerManager innerManager;


    void initConfig(FTRUMConfig config) {
        if (config.isRumEnable()) {
            innerManager = FTRUMInnerManager.get();
        }
    }

    /**
     * Records a completed action without opening a correlation window for subsequent events.
     *
     * @param actionName action name
     * @param actionType action type
     * @param duration   action duration in nanoseconds
     * @param property   extended attributes
     */
    public void addAction(String actionName, String actionType, long duration, HashMap<String, Object> property) {
        if (innerManager != null) {
            innerManager.addAction(actionName, actionType, duration, Utils.getCurrentNanoTime(), property);
        }
    }

    /**
     * Records a completed action without opening a correlation window for subsequent events.
     *
     * @param actionName action name
     * @param actionType action type
     */
    public void addAction(String actionName, String actionType) {
        if (innerManager != null) {
            innerManager.addAction(actionName, actionType, 0, Utils.getCurrentNanoTime(), null);
        }
    }

    /**
     * Records a completed action without opening a correlation window for subsequent events.
     *
     * @param actionName action name
     * @param actionType action type
     * @param property   extended attributes
     */
    public void addAction(String actionName, String actionType, HashMap<String, Object> property) {
        if (innerManager != null) {
            innerManager.addAction(actionName, actionType, 0, Utils.getCurrentNanoTime(), property);
        }
    }

    /**
     * Records a completed action without opening a correlation window for subsequent events.
     *
     * @param actionName action name
     * @param actionType action type
     * @param duration   action duration in nanoseconds
     */
    public void addAction(String actionName, String actionType, long duration) {
        if (innerManager != null) {
            innerManager.addAction(actionName, actionType, duration, Utils.getCurrentNanoTime(), null);
        }
    }

    /**
     * Starts a timed action that can link related Resource, LongTask and Error events.
     * A call made while an action remains open does not start another. To record each
     * occurrence independently, use {@link #addAction(String, String)}.
     *
     * @param actionName action name
     * @param actionType action type
     */
    public void startAction(String actionName, String actionType) {
        if (innerManager != null) {
            innerManager.startAction(actionName, actionType);
        }
    }

    /**
     * Starts a timed action with custom attributes. See {@link #startAction(String, String)}.
     *
     * @param actionName action name
     * @param actionType action type
     * @param property   additional attribute parameters
     */
    public void startAction(String actionName, String actionType, HashMap<String, Object> property) {
        if (innerManager != null) {
            innerManager.startAction(actionName, actionType, property);
        }
    }


    /**
     * Starts timing a manual resource. Use the same ID to stop timing and supply the network data.
     *
     * @param resourceId unique ID for this request, including when multiple requests share a URL
     */
    public void startResource(String resourceId) {
        if (innerManager != null) {
            innerManager.startResource(resourceId);
        }
    }


    /**
     * Starts timing a manual resource. Use the same ID to stop timing and supply the network data.
     *
     * @param resourceId unique ID for this request, including when multiple requests share a URL
     * @param property   additional attribute parameters
     */
    public void startResource(String resourceId, HashMap<String, Object> property) {
        if (innerManager != null) {
            innerManager.startResource(resourceId, property);
        }
    }


    /**
     * Stops timing the resource identified by {@code resourceId}.
     *
     * @param resourceId unique ID for this request, including when multiple requests share a URL
     */
    public void stopResource(String resourceId) {
        if (innerManager != null) {
            innerManager.stopResource(resourceId);
        }
    }


    /**
     * Stops timing the resource and adds the supplied attributes.
     *
     * @param resourceId unique ID for this request, including when multiple requests share a URL
     * @param property   additional attribute parameters
     */
    public void stopResource(final String resourceId, HashMap<String, Object> property) {
        if (innerManager != null) {
            innerManager.stopResource(resourceId, property);
        }
    }


    /**
     * Supplies loading time before starting a View with the same name.
     *
     * @param viewName view name
     * @param loadTime load time, nanosecond
     * @param loadStartTime actual load origin in Unix epoch nanoseconds; used only when
     *                      positive, before View start, and loadTime is positive
     */
    public void onCreateView(String viewName, long loadTime, long loadStartTime) {
        if (innerManager != null) {
            innerManager.onCreateView(viewName, loadTime, loadStartTime);
        }
    }

    /**
     * Supplies loading time before starting the named View. The View start is used for app state.
     * @param viewName name passed to the subsequent startView call
     * @param loadTime loading duration in nanoseconds
     */
    public void onCreateView(String viewName, long loadTime) {
        if (innerManager != null) {
            innerManager.onCreateView(viewName, loadTime);
        }
    }

    /**
     * Update the loading_time of the current view.
     *
     * @param duration loading time in nanoseconds
     */
    public void updateLoadTime(long duration) {
        if (innerManager != null) {
            innerManager.updateLoadTime(duration);
        }
    }

    /**
     * Starts a View with a caller-managed lifecycle.
     *
     * @param viewName current page name
     */
    public void startView(String viewName) {
        if (innerManager != null) {
            innerManager.startView(viewName);
        }
    }

    /**
     * Starts a View with a caller-managed lifecycle.
     *
     * @param viewName current page name
     * @param property additional attribute parameters
     */
    public void startView(String viewName, HashMap<String, Object> property) {
        if (innerManager != null) {
            innerManager.startView(viewName, property);
        }
    }

    /**
     * Starts a View with explicit lifecycle ownership for framework integrations.
     * Bridges map Manual = 0 to MANUAL and Automatic = 1 to AUTOMATIC.
     * Automatic Views close at the native background boundary. On foreground, start a
     * new View without resubmitting consumed loading data. Existing overloads remain Manual.
     *
     * @param viewName page name to report
     * @param property custom View attributes, or null
     * @param trackingType lifecycle ownership; null is treated as Manual
     * @return the new View ID, or null when RUM is disabled
     */
    public String startView(String viewName, HashMap<String, Object> property,
                            ViewTrackingType trackingType) {
        return innerManager == null ? null : innerManager.startView(viewName, property, trackingType);
    }

    /**
     * Stops only the matching current View. Use the ID returned by the typed startView
     * overload to ignore duplicate or delayed stops belonging to an earlier View.
     * Frameworks must cancel extra background stops: an unscoped stop arriving after
     * a new View starts cannot be attributed to the previous View.
     *
     * @param viewId ID returned when this View started
     * @param property attributes to merge into the matching View, or null
     */
    public void stopView(String viewId, HashMap<String, Object> property) {
        if (innerManager != null) innerManager.stopViewById(viewId, property);
    }


    /**
     * Ends the current View, if one is active.
     */
    public void stopView() {
        if (innerManager != null) {
            innerManager.stopView();
        }
    }

    /**
     * Ends the current View, if one is active.
     *
     * @param property additional attribute parameters
     */
    public void stopView(HashMap<String, Object> property) {
        if (innerManager != null) {
            innerManager.stopView(property, null);
        }
    }

    /**
     * Add error information, default AppState.RUN
     *
     * @param log       error stack or diagnostic details
     * @param message   short error message
     * @param errorType error type
     */
    public void addError(String log, String message, String errorType) {
        if (innerManager != null) {
            innerManager.addError(log, message, errorType, AppState.RUN);
        }
    }

    /**
     * Add error information
     *
     * @param log       error stack or diagnostic details
     * @param message   short error message
     * @param errorType error type
     * @param state     program running state
     */
    public void addError(String log, String message, String errorType, AppState state) {
        if (innerManager != null) {
            innerManager.addError(log, message, errorType, state);
        }
    }

    /**
     * Add error
     *
     * @param log       error stack or diagnostic details
     * @param message   short error message
     * @param errorType error type
     * @param state     program running state
     * @param dateline  time of occurrence in nanoseconds since the Unix epoch
     */
    public void addError(String log, String message, long dateline, String errorType,
                         AppState state) {
        if (innerManager != null) {
            innerManager.addError(log, message, dateline, errorType, state, null);
        }

    }


    /**
     * Add error information
     *
     * @param log       error stack or diagnostic details
     * @param message   short error message
     * @param errorType error type
     * @param state     program running state
     * @param property  additional attribute parameters
     */
    public void addError(String log, String message, String errorType, AppState state, HashMap<String, Object> property) {
        if (innerManager != null) {
            innerManager.addError(log, message, errorType, state, property);
        }
    }


    /**
     * Add error
     *
     * @param log       error stack or diagnostic details
     * @param message   short error message
     * @param errorType error type
     * @param state     program running state
     * @param dateline  time of occurrence in nanoseconds since the Unix epoch
     * @param property  additional attribute parameters
     */
    public void addError(String log, String message, long dateline, String errorType,
                         AppState state, HashMap<String, Object> property) {
        if (innerManager != null) {
            innerManager.addError(log, message, dateline, errorType, state, property, null);
        }

    }


    /**
     * Add error information
     *
     * @param log       error stack or diagnostic details
     * @param message   short error message
     * @param errorType error type
     * @param state     program running state
     */
    public void addError(String log, String message, ErrorType errorType, AppState state) {
        if (innerManager != null) {
            innerManager.addError(log, message, errorType.toString(), state);
        }
    }

    /**
     * Add error
     *
     * @param log       error stack or diagnostic details
     * @param message   short error message
     * @param errorType error type
     * @param state     program running state
     * @param dateline  time of occurrence in nanoseconds since the Unix epoch
     */
    public void addError(String log, String message, long dateline, ErrorType errorType,
                         AppState state) {
        if (innerManager != null) {
            innerManager.addError(log, message, dateline, errorType.toString(), state, null);
        }

    }


    /**
     * Add error information
     *
     * @param log       error stack or diagnostic details
     * @param message   short error message
     * @param errorType error type
     * @param state     program running state
     * @param property  additional attribute parameters
     */
    public void addError(String log, String message, ErrorType errorType, AppState state, HashMap<String, Object> property) {
        if (innerManager != null) {
            innerManager.addError(log, message, errorType.toString(), state, property);
        }
    }


    /**
     * Add error
     *
     * @param log       error stack or diagnostic details
     * @param message   short error message
     * @param errorType error type
     * @param state     program running state
     * @param dateline  time of occurrence in nanoseconds since the Unix epoch
     * @param property  additional attribute parameters
     */
    public void addError(String log, String message, long dateline, ErrorType errorType,
                         AppState state, HashMap<String, Object> property) {
        if (innerManager != null) {
            innerManager.addError(log, message, dateline, errorType.toString(), state, property, null);
        }

    }

    /**
     * Add long task
     *
     * @param log      log
     * @param duration duration, nanosecond
     * @param property additional attribute parameters
     */
    public void addLongTask(String log, long duration, HashMap<String, Object> property) {
        if (innerManager != null) {
            innerManager.addLongTask(log, duration, property);
        }
    }


    /**
     * Add long task
     *
     * @param log      log
     * @param duration duration, nanosecond
     */
    public void addLongTask(String log, long duration) {
        if (innerManager != null) {
            innerManager.addLongTask(log, duration);
        }
    }


    /**
     * Supplies HTTP details and network timings for a manually tracked request.
     * Use the ID from {@link #startResource(String)} and {@link #stopResource(String)}.
     *
     * @param resourceId    resource id
     * @param params        {@link ResourceParams} request content information
     * @param netStatusBean {@link  NetStatusBean} network performance indicators
     */
    public void addResource(String resourceId, ResourceParams params, NetStatusBean netStatusBean) {
        if (innerManager != null) {
            if (Utils.isNullOrEmpty(params.requestHeader)) {
                params.requestHeader = Utils.convertToHttpRawData(params.requestHeaderMap);
            }
            if (Utils.isNullOrEmpty(params.responseHeader)) {
                params.responseHeader = Utils.convertToHttpRawData(params.responseHeaderMap);
            }

            if (params.responseContentLength <= 0) {
                if (params.responseBody == null) {
                    params.responseBody = "";
                }
                params.responseContentLength = params.responseBody.length() + params.responseHeader.length();
            }

            innerManager.addResource(resourceId, params, netStatusBean);
        }
    }


    /**
     * Object release, clear to zero after SDK shutdown
     */
    public void release() {
        innerManager = null;
    }
}
