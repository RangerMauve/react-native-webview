
package com.reactnativecommunity.webview;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.DownloadManager;
import android.content.Context;

import com.facebook.react.uimanager.UIManagerModule;

import java.net.MalformedURLException;
import java.net.URL;
import com.facebook.react.bridge.ReactApplicationContext;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.common.build.ReactBuildConfig;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.uimanager.events.ContentSizeChangeEvent;
import com.facebook.react.uimanager.events.Event;
import com.facebook.react.uimanager.events.EventDispatcher;
import com.franmontiel.persistentcookiejar.PersistentCookieJar;
import com.franmontiel.persistentcookiejar.cache.SetCookieCache;
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor;
import com.facebook.react.uimanager.events.RCTEventEmitter;
import com.reactnativecommunity.webview.events.TopLoadingErrorEvent;
import com.reactnativecommunity.webview.events.TopLoadingFinishEvent;
import com.reactnativecommunity.webview.events.TopLoadingStartEvent;
import com.reactnativecommunity.webview.events.TopMessageEvent;
import com.reactnativecommunity.webview.events.TopLoadingProgressEvent;
import com.reactnativecommunity.webview.events.TopShouldStartLoadWithRequestEvent;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import com.reactnativecommunity.webview.events.WebViewUrlSchemeResult;
import com.reactnativecommunity.webview.events.WebViewUrlSchemeResultError;
import com.reactnativecommunity.webview.events.WebViewUrlSchemeResultFile;
import com.reactnativecommunity.webview.events.WebViewUrlSchemeResultRedirect;
import com.reactnativecommunity.webview.events.WebViewUrlSchemeResultResponse;

import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.OkHttpClient;

/**
 * Manages instances of {@link WebView}
 *
 * Can accept following commands:
 *  - GO_BACK
 *  - GO_FORWARD
 *  - RELOAD
 *  - LOAD_URL
 *
 * {@link WebView} instances could emit following direct events:
 *  - topLoadingFinish
 *  - topLoadingStart
 *  - topLoadingStart
 *  - topLoadingProgress
 *  - topShouldStartLoadWithRequest
 *
 * Each event will carry the following properties:
 *  - target - view's react tag
 *  - url - url set for the webview
 *  - loading - whether webview is in a loading state
 *  - title - title of the current page
 *  - canGoBack - boolean, whether there is anything on a history stack to go back
 *  - canGoForward - boolean, whether it is possible to request GO_FORWARD command
 */
@ReactModule(name = RNCWebViewManager.REACT_CLASS)
public class RNCWebViewManager extends SimpleViewManager<WebView> {

  protected static final String REACT_CLASS = "RNCWebView";
  private RNCWebViewPackage aPackage;

  protected static final String HTML_ENCODING = "UTF-8";
  protected static final String HTML_MIME_TYPE = "text/html";
  protected static final String JAVASCRIPT_INTERFACE = "ReactNativeWebView";

  protected static final String HTTP_METHOD_POST = "POST";

  public static final int COMMAND_GO_BACK = 1;
  public static final int COMMAND_GO_FORWARD = 2;
  public static final int COMMAND_RELOAD = 3;
  public static final int COMMAND_STOP_LOADING = 4;
  public static final int COMMAND_POST_MESSAGE = 5;
  public static final int COMMAND_INJECT_JAVASCRIPT = 6;
  public static final int COMMAND_LOAD_URL = 7;
  public static final int COMMAND_HANDLE_URL_SCHEME_RESPONSE = 8;

  // Use `webView.loadUrl("about:blank")` to reliably reset the view
  // state and release page resources (including any running JavaScript).
  protected static final String BLANK_URL = "about:blank";

  protected WebViewConfig mWebViewConfig;

  private final OkHttpClient httpClient;

  protected static class RNCWebViewClient extends WebViewClient {

    protected boolean mLastLoadFailed = false;
    protected @Nullable ReadableArray mUrlPrefixesForDefaultIntent;

    // This is a boolean that indicates whether or not the schemeUrlRequest feature is enabled.
    private boolean isOnUrlSchemeRequestEnabled = false;

    // This is mapping from the Request IDs back to a queue. When an event comes in from Javascript
    // it looks up the corresponding queue and puts the message into it. This then resumes
    // request processing in shouldInterceptRequest.
    private final Map<String, BlockingQueue<WebViewUrlSchemeResult>> requestQueueMap = new HashMap<>();

    // Eventually this should be configurable
    private final long SHOULD_INTERCEPT_REQUEST_TIMEOUT_MS = 5000;

    // Shared client for forwarding all of the requests for onUrlSchemeRequest.
    private final OkHttpClient httpClient;

    // URL to intercept requests from the browser
    private String baseInterceptUrl;

    protected RNCWebViewClient(OkHttpClient httpClient) {
      this.httpClient = httpClient;
    }

    @Override
    public void onPageFinished(WebView webView, String url) {
      super.onPageFinished(webView, url);

      if (!mLastLoadFailed) {
        RNCWebView reactWebView = (RNCWebView) webView;

        reactWebView.callInjectedJavaScript();

        emitFinishEvent(webView, url);
      }
    }

    @Override
    public void onPageStarted(WebView webView, String url, Bitmap favicon) {
      super.onPageStarted(webView, url, favicon);
      mLastLoadFailed = false;

      dispatchEvent(
          webView,
          new TopLoadingStartEvent(
              webView.getId(),
              createWebViewEvent(webView, url)));
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView webView, WebResourceRequest request) {
      String url = request.getUrl().toString();

      if (this.baseInterceptUrl == null) {
        return null;
      }

      if (!this.isOnUrlSchemeRequestEnabled) {
        return null;
      }

      if (!url.startsWith(this.baseInterceptUrl)) {
        return null;
      }

      String requestId = UUID.randomUUID().toString();

      BlockingQueue<WebViewUrlSchemeResult> queue = new LinkedBlockingQueue<>(1);

      try {
        requestQueueMap.put(requestId, queue);
        dispatchEvent(
            webView,
            new TopUrlSchemeRequestEvent(
                webView.getId(),
                requestId,
                url,
                request.getMethod(),
                request.getRequestHeaders()
            )
        );

        WebViewUrlSchemeResult result = queue.poll(
                SHOULD_INTERCEPT_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        if (result == null) {
          FLog.w(ReactConstants.TAG, "Timeout waiting for response from the server on '%s'", requestId);
          return CustomWebResourceResponse.buildErrorResponse("Timeout waiting waiting for response for '" + requestId + "'");
        }

        return handleUrlSchemeResult(result);
      } catch(InterruptedException exn) {
        FLog.w(ReactConstants.TAG, "Exception waiting for response from the server on '%s'", requestId, exn);
        return CustomWebResourceResponse.buildErrorResponse("Error waiting for response for '" + requestId + "'");
      } finally {
        requestQueueMap.remove(requestId);
      }
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
      dispatchEvent(view, new TopShouldStartLoadWithRequestEvent(view.getId(), url));
      return true;
    }


    @TargetApi(Build.VERSION_CODES.N)
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
      dispatchEvent(view, new TopShouldStartLoadWithRequestEvent(view.getId(), request.getUrl().toString()));
      return true;
    }

    @Override
    public void onReceivedError(
        WebView webView,
        int errorCode,
        String description,
        String failingUrl) {
      super.onReceivedError(webView, errorCode, description, failingUrl);
      mLastLoadFailed = true;

      // In case of an error JS side expect to get a finish event first, and then get an error event
      // Android WebView does it in the opposite way, so we need to simulate that behavior
      emitFinishEvent(webView, failingUrl);

      WritableMap eventData = createWebViewEvent(webView, failingUrl);
      eventData.putDouble("code", errorCode);
      eventData.putString("description", description);

      dispatchEvent(
          webView,
          new TopLoadingErrorEvent(webView.getId(), eventData));
    }

    protected void emitFinishEvent(WebView webView, String url) {
      dispatchEvent(
          webView,
          new TopLoadingFinishEvent(
              webView.getId(),
              createWebViewEvent(webView, url)));
    }

    protected WritableMap createWebViewEvent(WebView webView, String url) {
      WritableMap event = Arguments.createMap();
      event.putDouble("target", webView.getId());
      // Don't use webView.getUrl() here, the URL isn't updated to the new value yet in callbacks
      // like onPageFinished
      event.putString("url", url);
      event.putBoolean("loading", !mLastLoadFailed && webView.getProgress() != 100);
      event.putString("title", webView.getTitle());
      event.putBoolean("canGoBack", webView.canGoBack());
      event.putBoolean("canGoForward", webView.canGoForward());
      return event;
    }

    public void setUrlPrefixesForDefaultIntent(ReadableArray specialUrls) {
      mUrlPrefixesForDefaultIntent = specialUrls;
    }

    public void setOriginWhitelist(List<Pattern> originWhitelist) {
      mOriginWhitelist = originWhitelist;
    }

    public void setIsOnUrlSchemeRequestEnabled(boolean isOnUrlSchemeRequestEnabled) {
      this.isOnUrlSchemeRequestEnabled = isOnUrlSchemeRequestEnabled;
    }

    public void setBaseInterceptUrl(String baseInterceptUrl) {
      this.baseInterceptUrl = baseInterceptUrl;
    }

    public void handleUrlSchemeResponse(@Nullable ReadableArray args) {
      if (args == null) {
        FLog.w(ReactConstants.TAG, "handleUrlSchemeResponse: Received null arguments");
        return;
      }

      if (args.size() != 1) {
        FLog.w(ReactConstants.TAG, "handleUrlSchemeResponse: Received an invalid number of arguments: " + args);
        return;
      }

      ReadableMap argMap = args.getMap(0);

      if (argMap == null) {
        FLog.w(ReactConstants.TAG, "handleUrlSchemeResponse: First argument is not a map.");
        return;
      }

      String requestId = argMap.getString("requestId");
      if (requestId == null) {
        FLog.w(ReactConstants.TAG, "handleUrlSchemeResponse: Missing requestId.");
        return;
      }

      BlockingQueue queue = requestQueueMap.get(requestId);

      if (queue == null) {
        // This might not be an error if there is a timeout
        FLog.w(ReactConstants.TAG, "handleUrlSchemeResponse: Missing future for '%s'", requestId);
        return;
      }

      WebViewUrlSchemeResult result = WebViewUrlSchemeResult.Companion.from(argMap);

      if (!queue.offer(result)) {
        FLog.w(ReactConstants.TAG, "handleUrlSchemeResponse: Received two messages for '%s'", requestId);
      }
    }

    private WebResourceResponse handleUrlSchemeResult(WebViewUrlSchemeResult result) {
      if (result instanceof WebViewUrlSchemeResultError) {
        WebViewUrlSchemeResultError error = (WebViewUrlSchemeResultError)result;

        Map<String, String> body = new HashMap<>();
        body.put("status", "error");
        body.put("message", error.getMessage());
        String bodyString = new JSONObject(body).toString();

          return new CustomWebResourceResponse(null,
                  "utf-8", HttpURLConnection.HTTP_INTERNAL_ERROR,
                  new HashMap<>(),
                  new ByteArrayInputStream(bodyString.getBytes(StandardCharsets.UTF_8)));
      } else if (result instanceof  WebViewUrlSchemeResultResponse) {
        WebViewUrlSchemeResultResponse response = (WebViewUrlSchemeResultResponse)result;
        Map<String, String> headers = response.getHeaders();

        // Header keys are all lower cased since they are case insensitive.
        String mimeType = headers.get("content-type");
        if (mimeType == null) {
          mimeType = "text/html";
        }
        // You can't include the charset in the mimeType, Android will renders
        // this as a blank page:
        // https://stackoverflow.com/questions/15937063/webview-only-showing-raw-html-text-on-some-pages
        mimeType = mimeType.split(";")[0];

        return new CustomWebResourceResponse(mimeType,
                "UTF-8", response.getStatus(),
                headers,
                new ByteArrayInputStream(response.getBody().getBytes(StandardCharsets.UTF_8)));
      } else if (result instanceof  WebViewUrlSchemeResultRedirect) {
        WebViewUrlSchemeResultRedirect redirect = (WebViewUrlSchemeResultRedirect)result;
        Map<String, String> redirectHeaders = redirect.getHeaders();

        // application/x-www-form-urlencoded is the default media type from curl.
        String mediaTypeString = redirectHeaders.get("content-type");
        if (mediaTypeString == null) {
          mediaTypeString = "application/x-www-form-urlencoded";
        }
        okhttp3.MediaType mediaType = okhttp3.MediaType.get(mediaTypeString);

        okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                .url(redirect.getUrl())
                .headers(okhttp3.Headers.of(redirectHeaders));

        String redirectBody = redirect.getBody();
        okhttp3.RequestBody body = null;
        if (redirectBody != null) {
          body = okhttp3.RequestBody.create(mediaType, redirect.getBody());
        }

        builder.method(redirect.getMethod(), body);
        okhttp3.Request request = builder.build();

        try {
          okhttp3.Response response = this.httpClient.newCall(request).execute();

          int status = response.code();
          okhttp3.ResponseBody responseBody = response.body();
          okhttp3.MediaType contentType = responseBody.contentType();

          String encoding = null;
          if (contentType != null && contentType.charset() != null) {
            encoding = contentType.charset().toString();
          }

          Map<String, String> responseHeaders = new HashMap<>();

          for (String headerName : response.headers().names()) {
            responseHeaders.put(headerName, response.headers(headerName).get(0));
          }

          String mimeType = null;
          if (contentType != null) {
            mimeType = contentType.type() + "/" + contentType.subtype();
          }

          return new CustomWebResourceResponse(mimeType,
                  encoding, status,
                  responseHeaders,
                  responseBody.byteStream());
        } catch (IOException exn) {
          // TODO: Should this return null, or return an error?
          FLog.w(ReactConstants.TAG, "Error fetching request from: '" + redirect.getUrl() + "'", exn);
          return null;
        }
      } else if (result instanceof WebViewUrlSchemeResultFile) {
        WebViewUrlSchemeResultFile file = (WebViewUrlSchemeResultFile)result;

        Map<String, String> responseHeaders = file.getHeaders();

        // Header keys are all lower cased since they are case insensitive.
        String mediaTypeString= responseHeaders.get("content-type");
        if (mediaTypeString == null) {
          mediaTypeString = "text/html";
        }

        okhttp3.MediaType mediaType = okhttp3.MediaType.get(mediaTypeString);

        String mimeType = null;
        if (mediaType != null) {
          // You can't include the charset in the mimetype, Android just renders
          // this as a blank page:
          // https://stackoverflow.com/questions/15937063/webview-only-showing-raw-html-text-on-some-pages
          mimeType = mediaType.type() + "/" + mediaType.subtype();
        }

        String encoding = null;
        if (mediaType != null && mediaType.charset() != null) {
          encoding = mediaType.charset().toString();
        }

        try {
          FileInputStream inputStream = new FileInputStream(file.getFile());
          return new CustomWebResourceResponse(mimeType, encoding, HttpURLConnection.HTTP_OK,
                  responseHeaders, inputStream);
        } catch (FileNotFoundException exn) {
          FLog.w(ReactConstants.TAG, "The file '" + file.getFile() + "' does not exist, ignoring", exn);
          return null;
        }
      }

      FLog.w(ReactConstants.TAG, "Unknown type of response: '" + result + "'");
      return null;

    }
  }

  /**
   * Subclass of {@link WebView} that implements {@link LifecycleEventListener} interface in order
   * to call {@link WebView#destroy} on activity destroy event and also to clear the client
   */
  protected static class RNCWebView extends WebView implements LifecycleEventListener {
    protected @Nullable String injectedJS;
    protected boolean messagingEnabled = false;
    protected @Nullable RNCWebViewClient mRNCWebViewClient;
    protected boolean sendContentSizeChangeEvents = false;
    public void setSendContentSizeChangeEvents(boolean sendContentSizeChangeEvents) {
      this.sendContentSizeChangeEvents = sendContentSizeChangeEvents;
    }


    protected class RNCWebViewBridge {
      RNCWebView mContext;

      RNCWebViewBridge(RNCWebView c) {
        mContext = c;
      }

      /**
       * This method is called whenever JavaScript running within the web view calls:
       *   - window[JAVASCRIPT_INTERFACE].postMessage
       */
      @JavascriptInterface
      public void postMessage(String message) {
        mContext.onMessage(message);
      }
    }

    /**
     * WebView must be created with an context of the current activity
     *
     * Activity Context is required for creation of dialogs internally by WebView
     * Reactive Native needed for access to ReactNative internal system functionality
     *
     */
    public RNCWebView(ThemedReactContext reactContext) {
      super(reactContext);
    }

    @Override
    public void onHostResume() {
      // do nothing
    }

    @Override
    public void onHostPause() {
      // do nothing
    }

    @Override
    public void onHostDestroy() {
      cleanupCallbacksAndDestroy();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
      super.onSizeChanged(w, h, ow, oh);

      if (sendContentSizeChangeEvents) {
        dispatchEvent(
          this,
          new ContentSizeChangeEvent(
            this.getId(),
            w,
            h
          )
        );
      }
    }

    @Override
    public void setWebViewClient(WebViewClient client) {
      super.setWebViewClient(client);
      mRNCWebViewClient = (RNCWebViewClient)client;
    }

    public @Nullable RNCWebViewClient getRNCWebViewClient() {
      return mRNCWebViewClient;
    }

    public void setInjectedJavaScript(@Nullable String js) {
      injectedJS = js;
    }

    protected RNCWebViewBridge createRNCWebViewBridge(RNCWebView webView) {
      return new RNCWebViewBridge(webView);
    }

    @SuppressLint("AddJavascriptInterface")
    public void setMessagingEnabled(boolean enabled) {
      if (messagingEnabled == enabled) {
        return;
      }

      messagingEnabled = enabled;

      if (enabled) {
        addJavascriptInterface(createRNCWebViewBridge(this), JAVASCRIPT_INTERFACE);
      } else {
        removeJavascriptInterface(JAVASCRIPT_INTERFACE);
      }
    }

    protected void evaluateJavascriptWithFallback(String script) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
        evaluateJavascript(script, null);
        return;
      }

      try {
        loadUrl("javascript:" + URLEncoder.encode(script, "UTF-8"));
      } catch (UnsupportedEncodingException e) {
        // UTF-8 should always be supported
        throw new RuntimeException(e);
      }
    }

    public void callInjectedJavaScript() {
      if (getSettings().getJavaScriptEnabled() &&
          injectedJS != null &&
          !TextUtils.isEmpty(injectedJS)) {
        evaluateJavascriptWithFallback("(function() {\n" + injectedJS + ";\n})();");
      }
    }

    public void onMessage(String message) {
      dispatchEvent(this, new TopMessageEvent(this.getId(), message));
    }

    protected void cleanupCallbacksAndDestroy() {
      setWebViewClient(null);
      destroy();
    }
  }

  public RNCWebViewManager(ReactApplicationContext reactContext) {
    this(reactContext, new WebViewConfig() {
      public void configWebView(WebView webView) {
      }
    });
  }

  public RNCWebViewManager(ReactApplicationContext reactContext, WebViewConfig webViewConfig) {
    mWebViewConfig = webViewConfig;

    Context context = reactContext.getApplicationContext();
    PersistentCookieJar cookieJar = new PersistentCookieJar(new SetCookieCache(), new SharedPrefsCookiePersistor(context));
    this.httpClient = new OkHttpClient.Builder().cookieJar(cookieJar).build();
  }

  @Override
  public String getName() {
    return REACT_CLASS;
  }

  protected RNCWebView createRNCWebViewInstance(ThemedReactContext reactContext) {
    return new RNCWebView(reactContext);
  }

  @Override
  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  protected WebView createViewInstance(ThemedReactContext reactContext) {
    RNCWebView webView = createRNCWebViewInstance(reactContext);
    webView.setWebChromeClient(new WebChromeClient() {
      @Override
      public boolean onConsoleMessage(ConsoleMessage message) {
        if (ReactBuildConfig.DEBUG) {
          return super.onConsoleMessage(message);
        }
        // Ignore console logs in non debug builds.
        return true;
      }


    @Override
    public void onProgressChanged(WebView webView, int newProgress) {
        super.onProgressChanged(webView, newProgress);
        WritableMap event = Arguments.createMap();
        event.putDouble("target", webView.getId());
        event.putString("title", webView.getTitle());
        event.putBoolean("canGoBack", webView.canGoBack());
        event.putBoolean("canGoForward", webView.canGoForward());
        event.putDouble("progress", (float)newProgress/100);
        dispatchEvent(
                  webView,
                  new TopLoadingProgressEvent(
                      webView.getId(),
                      event));
    }

      @Override
      public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
        callback.invoke(origin, true, false);
      }

      protected void openFileChooser(ValueCallback<Uri> filePathCallback, String acceptType) {
        getModule().startPhotoPickerIntent(filePathCallback, acceptType);
      }
      protected void openFileChooser(ValueCallback<Uri> filePathCallback) {
        getModule().startPhotoPickerIntent(filePathCallback, "");
      }
      protected void openFileChooser(ValueCallback<Uri> filePathCallback, String acceptType, String capture) {
        getModule().startPhotoPickerIntent(filePathCallback, acceptType);
      }

      @TargetApi(Build.VERSION_CODES.LOLLIPOP)
      @Override
      public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
        String[] acceptTypes = fileChooserParams.getAcceptTypes();
        boolean allowMultiple = fileChooserParams.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;
        Intent intent = fileChooserParams.createIntent();
        return getModule().startPhotoPickerIntent(filePathCallback, intent, acceptTypes, allowMultiple);
      }
    });
    reactContext.addLifecycleEventListener(webView);
    mWebViewConfig.configWebView(webView);
    WebSettings settings = webView.getSettings();
    settings.setBuiltInZoomControls(true);
    settings.setDisplayZoomControls(false);
    settings.setDomStorageEnabled(true);

    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
      settings.setAllowFileAccessFromFileURLs(false);
      setAllowUniversalAccessFromFileURLs(webView, false);
    }
    setMixedContentMode(webView, "never");

    // Fixes broken full-screen modals/galleries due to body height being 0.
    webView.setLayoutParams(
            new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));

    setGeolocationEnabled(webView, false);
    if (true || ReactBuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      WebView.setWebContentsDebuggingEnabled(true);
    }

    webView.setDownloadListener(new DownloadListener() {
      public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
        RNCWebViewModule module = getModule();

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));

        String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
        String downloadMessage = "Downloading " + fileName;

        //Attempt to add cookie, if it exists
        URL urlObj = null;
        try {
          urlObj = new URL(url);
          String baseUrl = urlObj.getProtocol() + "://" + urlObj.getHost();
          String cookie = CookieManager.getInstance().getCookie(baseUrl);
          request.addRequestHeader("Cookie", cookie);
          System.out.println("Got cookie for DownloadManager: " + cookie);
        } catch (MalformedURLException e) {
          System.out.println("Error getting cookie for DownloadManager: " + e.toString());
          e.printStackTrace();
        }

        //Finish setting up request
        request.addRequestHeader("User-Agent", userAgent);
        request.setTitle(fileName);
        request.setDescription(downloadMessage);
        request.allowScanningByMediaScanner();
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

        module.setDownloadRequest(request);

        if (module.grantFileDownloaderPermissions()) {
          module.downloadFile();
        }
      }
    });

    return webView;
  }

  @ReactProp(name = "javaScriptEnabled")
  public void setJavaScriptEnabled(WebView view, boolean enabled) {
    view.getSettings().setJavaScriptEnabled(enabled);
  }

  @ReactProp(name = "showsHorizontalScrollIndicator")
  public void setShowsHorizontalScrollIndicator(WebView view, boolean enabled) {
    view.setHorizontalScrollBarEnabled(enabled);
  }

  @ReactProp(name = "showsVerticalScrollIndicator")
  public void setShowsVerticalScrollIndicator(WebView view, boolean enabled) {
    view.setVerticalScrollBarEnabled(enabled);
  }

  @ReactProp(name = "cacheEnabled")
  public void setCacheEnabled(WebView view, boolean enabled) {
    if (enabled) {
      Context ctx = view.getContext();
      if (ctx != null) {
        view.getSettings().setAppCachePath(ctx.getCacheDir().getAbsolutePath());
        view.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
        view.getSettings().setAppCacheEnabled(true);
      }
    } else {
      view.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
      view.getSettings().setAppCacheEnabled(false);
    }
  }

  @ReactProp(name = "androidHardwareAccelerationDisabled")
  public void setHardwareAccelerationDisabled(WebView view, boolean disabled) {
    if (disabled) {
      view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }
  }

  @ReactProp(name = "overScrollMode")
  public void setOverScrollMode(WebView view, String overScrollModeString) {
    Integer overScrollMode;
    switch (overScrollModeString) {
      case "never":
        overScrollMode = View.OVER_SCROLL_NEVER;
        break;
      case "content":
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS;
        break;
      case "always":
      default:
        overScrollMode = View.OVER_SCROLL_ALWAYS;
        break;
    }
    view.setOverScrollMode(overScrollMode);
  }

  @ReactProp(name = "thirdPartyCookiesEnabled")
  public void setThirdPartyCookiesEnabled(WebView view, boolean enabled) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      CookieManager.getInstance().setAcceptThirdPartyCookies(view, enabled);
    }
  }

  @ReactProp(name = "scalesPageToFit")
  public void setScalesPageToFit(WebView view, boolean enabled) {
    view.getSettings().setLoadWithOverviewMode(enabled);
    view.getSettings().setUseWideViewPort(enabled);
  }

  @ReactProp(name = "domStorageEnabled")
  public void setDomStorageEnabled(WebView view, boolean enabled) {
    view.getSettings().setDomStorageEnabled(enabled);
  }

  @ReactProp(name = "userAgent")
  public void setUserAgent(WebView view, @Nullable String userAgent) {
    if (userAgent != null) {
      // TODO(8496850): Fix incorrect behavior when property is unset (uA == null)
      view.getSettings().setUserAgentString(userAgent);
    }
  }

  @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
  @ReactProp(name = "mediaPlaybackRequiresUserAction")
  public void setMediaPlaybackRequiresUserAction(WebView view, boolean requires) {
    view.getSettings().setMediaPlaybackRequiresUserGesture(requires);
  }

  @ReactProp(name = "allowUniversalAccessFromFileURLs")
  public void setAllowUniversalAccessFromFileURLs(WebView view, boolean allow) {
    view.getSettings().setAllowUniversalAccessFromFileURLs(allow);
  }

  @ReactProp(name = "saveFormDataDisabled")
  public void setSaveFormDataDisabled(WebView view, boolean disable) {
    view.getSettings().setSaveFormData(!disable);
  }

  @ReactProp(name = "injectedJavaScript")
  public void setInjectedJavaScript(WebView view, @Nullable String injectedJavaScript) {
    ((RNCWebView) view).setInjectedJavaScript(injectedJavaScript);
  }

  @ReactProp(name = "messagingEnabled")
  public void setMessagingEnabled(WebView view, boolean enabled) {
    ((RNCWebView) view).setMessagingEnabled(enabled);
  }

  @ReactProp(name = "source")
  public void setSource(WebView view, @Nullable ReadableMap source) {
    if (source != null) {
      if (source.hasKey("html")) {
        String html = source.getString("html");
        if (source.hasKey("baseUrl")) {
          view.loadDataWithBaseURL(
              source.getString("baseUrl"), html, HTML_MIME_TYPE, HTML_ENCODING, null);
        } else {
          view.loadData(html, HTML_MIME_TYPE + "; charset=" + HTML_ENCODING, null);
        }
        return;
      }
      if (source.hasKey("uri")) {
        String url = source.getString("uri");
        String previousUrl = view.getUrl();
        if (previousUrl != null && previousUrl.equals(url)) {
          return;
        }
        if (source.hasKey("method")) {
          String method = source.getString("method");
          if (method.equalsIgnoreCase(HTTP_METHOD_POST)) {
            byte[] postData = null;
            if (source.hasKey("body")) {
              String body = source.getString("body");
              try {
                postData = body.getBytes("UTF-8");
              } catch (UnsupportedEncodingException e) {
                postData = body.getBytes();
              }
            }
            if (postData == null) {
              postData = new byte[0];
            }
            view.postUrl(url, postData);
            return;
          }
        }
        HashMap<String, String> headerMap = new HashMap<>();
        if (source.hasKey("headers")) {
          ReadableMap headers = source.getMap("headers");
          ReadableMapKeySetIterator iter = headers.keySetIterator();
          while (iter.hasNextKey()) {
            String key = iter.nextKey();
            if ("user-agent".equals(key.toLowerCase(Locale.ENGLISH))) {
              if (view.getSettings() != null) {
                view.getSettings().setUserAgentString(headers.getString(key));
              }
            } else {
              headerMap.put(key, headers.getString(key));
            }
          }
        }
        view.loadUrl(url, headerMap);
        return;
      }
    }
    view.loadUrl(BLANK_URL);
  }

  @ReactProp(name = "onContentSizeChange")
  public void setOnContentSizeChange(WebView view, boolean sendContentSizeChangeEvents) {
    ((RNCWebView) view).setSendContentSizeChangeEvents(sendContentSizeChangeEvents);
  }

  @ReactProp(name = "mixedContentMode")
  public void setMixedContentMode(WebView view, @Nullable String mixedContentMode) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      if (mixedContentMode == null || "never".equals(mixedContentMode)) {
        view.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
      } else if ("always".equals(mixedContentMode)) {
        view.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
      } else if ("compatibility".equals(mixedContentMode)) {
        view.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
      }
    }
  }

  @ReactProp(name = "urlPrefixesForDefaultIntent")
  public void setUrlPrefixesForDefaultIntent(
      WebView view,
      @Nullable ReadableArray urlPrefixesForDefaultIntent) {
    RNCWebViewClient client = ((RNCWebView) view).getRNCWebViewClient();
    if (client != null && urlPrefixesForDefaultIntent != null) {
      client.setUrlPrefixesForDefaultIntent(urlPrefixesForDefaultIntent);
    }
  }

  @ReactProp(name = "allowFileAccess")
  public void setAllowFileAccess(
    WebView view,
    @Nullable Boolean allowFileAccess) {
    view.getSettings().setAllowFileAccess(allowFileAccess != null && allowFileAccess);
  }

  @ReactProp(name = "geolocationEnabled")
  public void setGeolocationEnabled(
    WebView view,
    @Nullable Boolean isGeolocationEnabled) {
    view.getSettings().setGeolocationEnabled(isGeolocationEnabled != null && isGeolocationEnabled);
  }

  @ReactProp(name = "originWhitelist")
  public void setOriginWhitelist(
    WebView view,
    @Nullable ReadableArray originWhitelist) {
    RNCWebViewClient client = ((RNCWebView) view).getRNCWebViewClient();
    if (client != null && originWhitelist != null) {
      List<Pattern> whiteList = new LinkedList<>();
      for (int i = 0 ; i < originWhitelist.size() ; i++) {
        whiteList.add(Pattern.compile(originWhitelist.getString(i)));
      }
      client.setOriginWhitelist(whiteList);
    }
  }

  @ReactProp(name = "onUrlSchemeRequest")
  public void setOnUrlSchemeRequest(
    WebView webView,
    boolean isOnUrlSchemeRequestEnabled) {
    RNCWebViewClient client = ((RNCWebView) webView).getRNCWebViewClient();
    if (client != null) {
      client.setIsOnUrlSchemeRequestEnabled(isOnUrlSchemeRequestEnabled);
    }
  }

  @ReactProp(name = "baseInterceptUrl")
  public void setBaseInterceptUrl(
          WebView webView,
          String baseInterceptUrl) {

    RNCWebViewClient client = ((RNCWebView) webView).getRNCWebViewClient();
    if (client != null) {
      client.setBaseInterceptUrl(baseInterceptUrl);
    }
  }

  @Override
  protected void addEventEmitters(ThemedReactContext reactContext, WebView view) {
    // Do not register default touch emitter and let WebView implementation handle touches
    view.setWebViewClient(new RNCWebViewClient(this.httpClient));
  }

  @Override
  public Map getExportedCustomDirectEventTypeConstants() {
    Map export = super.getExportedCustomDirectEventTypeConstants();
    if (export == null) {
      export = MapBuilder.newHashMap();
    }
    export.put(TopLoadingProgressEvent.EVENT_NAME, MapBuilder.of("registrationName", "onLoadingProgress"));
    export.put(TopShouldStartLoadWithRequestEvent.EVENT_NAME, MapBuilder.of("registrationName", "onShouldStartLoadWithRequest"));
    return export;
  }

  @Override
  public @Nullable Map<String, Integer> getCommandsMap() {
    Map<String, Integer> commandMap = MapBuilder.newHashMap()

    commandMap.put("goBack", COMMAND_GO_BACK);
    commandMap.put("goForward", COMMAND_GO_FORWARD);
    commandMap.put("reload", COMMAND_RELOAD);
    commandMap.put("stopLoading", COMMAND_STOP_LOADING);
    commandMap.put("postMessage", COMMAND_POST_MESSAGE);
    commandMap.put("injectJavaScript", COMMAND_INJECT_JAVASCRIPT);
    commandMap.put("handleUrlSchemeResponse", COMMAND_HANDLE_URL_SCHEME_RESPONSE);
    commandMap.put("loadUrl", COMMAND_LOAD_URL);

    return commandMap;
  }

  @Override
  public void receiveCommand(WebView root, int commandId, @Nullable ReadableArray args) {
    switch (commandId) {
      case COMMAND_GO_BACK:
        root.goBack();
        break;
      case COMMAND_GO_FORWARD:
        root.goForward();
        break;
      case COMMAND_RELOAD:
        root.reload();
        break;
      case COMMAND_STOP_LOADING:
        root.stopLoading();
        break;
      case COMMAND_POST_MESSAGE:
        try {
          RNCWebView reactWebView = (RNCWebView) root;
          JSONObject eventInitDict = new JSONObject();
          eventInitDict.put("data", args.getString(0));
          reactWebView.evaluateJavascriptWithFallback("(function () {" +
            "var event;" +
            "var data = " + eventInitDict.toString() + ";" +
            "try {" +
              "event = new MessageEvent('message', data);" +
            "} catch (e) {" +
              "event = document.createEvent('MessageEvent');" +
              "event.initMessageEvent('message', true, true, data.data, data.origin, data.lastEventId, data.source);" +
            "}" +
            "document.dispatchEvent(event);" +
          "})();");
        } catch (JSONException e) {
          throw new RuntimeException(e);
        }
        break;
      case COMMAND_INJECT_JAVASCRIPT: {
        RNCWebView reactWebView = (RNCWebView) root;
        reactWebView.evaluateJavascriptWithFallback(args.getString(0));
        break;
      case COMMAND_LOAD_URL:
        if (args == null) {
          throw new RuntimeException("Arguments for loading an url are null!");
        }
        root.loadUrl(args.getString(0));
        break;
      }
      case COMMAND_HANDLE_URL_SCHEME_RESPONSE: {
        RNCWebView reactWebView = (RNCWebView) root;
        RNCWebViewClient client = reactWebView.getRNCWebViewClient();
        if (client != null) {
          client.handleUrlSchemeResponse(args);
        }
        break;
      }
    }
  }

  @Override
  public void onDropViewInstance(WebView webView) {
    super.onDropViewInstance(webView);
    ((ThemedReactContext) webView.getContext()).removeLifecycleEventListener((RNCWebView) webView);
    ((RNCWebView) webView).cleanupCallbacksAndDestroy();
  }

  protected static void dispatchEvent(WebView webView, Event event) {
    ReactContext reactContext = (ReactContext) webView.getContext();
    EventDispatcher eventDispatcher =
      reactContext.getNativeModule(UIManagerModule.class).getEventDispatcher();
    eventDispatcher.dispatchEvent(event);
  }

  public RNCWebViewPackage getPackage() {
    return this.aPackage;
  }

  public void setPackage(RNCWebViewPackage aPackage) {
    this.aPackage = aPackage;
  }

  public RNCWebViewModule getModule() {
    return this.aPackage.getModule();
  }
}
