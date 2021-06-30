package org.inaturalist.inatcamera.nativecamera;

import timber.log.Timber;
import com.facebook.react.bridge.ReactApplicationContext;
import java.util.Date;
import android.util.Log;
import com.facebook.react.bridge.*;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.events.RCTEventEmitter;
import android.view.View;
import java.text.SimpleDateFormat;

public class LogEventTree extends Timber.DebugTree {

    public static final String EVENT_NAME_ON_LOG = "onLog";

    private ThemedReactContext mContext;
    private View mView;

    public LogEventTree(ThemedReactContext context, View view) {
        super();

        mContext = context;
        mView = view;
    }

    @Override
    protected void log(int priority, String tag, String message, Throwable t) {
        StringBuilder builder = new StringBuilder();

        try {
            Date now = new Date();
            SimpleDateFormat dateString = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

            String formattedMessage = builder
                    .append(dateString.format(now))
                    .append(": ")
                    .append(tag)
                    .append(": ")
                    .append(message)
                    .toString();

            WritableMap event = Arguments.createMap();
            event.putString("log", formattedMessage);
            mContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                    mView.getId(),
                    EVENT_NAME_ON_LOG,
                    event);
        } catch (OutOfMemoryError e) {
            // Can't print to log in this case since the OOM exception is within the log tree itself
            e.printStackTrace();
        }
    }

    @Override
    protected String createStackElementTag(StackTraceElement element) {
        // Add log statements line number to the log
        return super.createStackElementTag(element) + " - #" + element.getLineNumber();
    }
}