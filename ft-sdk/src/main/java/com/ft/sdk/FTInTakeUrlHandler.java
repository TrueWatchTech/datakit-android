package com.ft.sdk;

/**
 * Filters URLs for automatic RUM resource collection. Configure with
 * {@link FTRUMConfig#setResourceUrlHandler(FTInTakeUrlHandler)}.
 *
 * @author Brandon
 */
public interface FTInTakeUrlHandler {
    /**
     * Checks whether to exclude a request URL.
     *
     * @param url full URL of the request
     * @return true to exclude this URL; false to allow normal collection
     */
    boolean isInTakeUrl(String url);
}
