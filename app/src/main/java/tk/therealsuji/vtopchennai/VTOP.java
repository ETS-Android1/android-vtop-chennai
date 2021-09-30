package tk.therealsuji.vtopchennai;

import static android.content.Context.ALARM_SERVICE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.os.Build;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.ViewStub;
import android.view.animation.AccelerateInterpolator;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;

public class VTOP {
    Context context;

    WebView webView;
    Dialog downloadDialog;
    ImageView captcha;
    EditText captchaView;
    ProgressBar loading, progressBar;
    Spinner selectSemester;
    LinearLayout captchaLayout, progressLayout, semesterLayout;
    ViewStub captchaStub, semesterStub, progressStub;
    Queue<View> layoutQueue;

    String username, password, semesterID;

    /*
        DARK is used to change the colours of the captcha image when in dark mode
     */
    private static final float[] DARK = {
            -0.853f, 0, 0, 0, 255, // R
            0, -0.853f, 0, 0, 255, // G
            0, 0, -0.853f, 0, 255, // B
            0, 0, 0, 1.0f, 1.0f  // A
    };

    SharedPreferences sharedPreferences;
    SQLiteDatabase myDatabase;
    TextView downloading, progressText;
    int counter, lastDownload;
    float pixelDensity;
    boolean isOpened, isCaptchaInflated, isSemesterInflated, isProgressInflated, isCompressing, terminateDownload, isInProgress, isVerifyingCaptcha;

    ErrorHandler errorHandler;

    @SuppressLint("SetJavaScriptEnabled")
    public VTOP(final Context context) {
        this.context = context;
        webView = new WebView(context);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setVerticalScrollBarEnabled(false);
        webView.getSettings().setJavaScriptEnabled(true);
        errorHandler = new ErrorHandler(this.context, webView.getSettings().getUserAgentString());
        webView.getSettings().setUserAgentString("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.99 Mobile Safari/537.36");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (terminateDownload) {
                    return;
                }

                if (!isOpened) {
                    if (counter >= 60) {    // If it has tried to open the sign in page for over 60 times and failed, something is wrong
                        Toast.makeText(context, "Error 101, we had some trouble connecting to the server. Please try again later.", Toast.LENGTH_LONG).show();
                        myDatabase.close();
                        downloadDialog.dismiss();
                        return;
                    }

                    isOpened = true;
                    openSignIn();
                    ++counter;
                }
            }
        });

        if (ErrorHandler.isPreRelease) {
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                    int lineNumber = consoleMessage.lineNumber();
                    String message = consoleMessage.message();
                    String source = consoleMessage.sourceId();
                    String messageLevel = consoleMessage.messageLevel().toString().toLowerCase();

                    if (messageLevel.equals("log")) {
                        return super.onConsoleMessage(consoleMessage);
                    }

//                  errorHandler.append("<code class=\"").append(messageLevel).append("\">[INFO:CONSOLE(").append(lineNumber).append(")] ").append("\"").append(message).append("\", source: ").append(source).append("</code>\n");
                    errorHandler.append("[").append(messageLevel.toUpperCase()).append(":CONSOLE(").append(lineNumber).append(")] ").append("\"").append(message).append("\", source: ").append(source).append("\n");
                    return super.onConsoleMessage(consoleMessage);
                }
            });
        }


        webView.addJavascriptInterface(this, "Android");
    }

    /*
        Start referencing the views
     */
    public void start(final Dialog downloadDialog) {
        terminateDownload = false;      // If the download has been terminated, it has to be reset when asked to download again
        counter = 0;                    // If the counter has turned to 60, it won't allow future downloads unless it has been reset

        this.downloadDialog = downloadDialog;

        /*
            Getting the saved credentials
         */
        SharedPreferences encryptedSharedPreferences;

        try {
            MasterKey masterKey = new MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            encryptedSharedPreferences = EncryptedSharedPreferences.create(
                    context,
                    "credentials",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            if (ErrorHandler.isPreRelease) {
                errorHandler.appendStackTrace(e);
            }

            errorHandler.error("001");
            downloadDialog.dismiss();
            return;
        }

        username = encryptedSharedPreferences.getString("username", null);
        password = encryptedSharedPreferences.getString("password", null);

        loading = downloadDialog.findViewById(R.id.loading);

        captchaStub = downloadDialog.findViewById(R.id.captchaStub);
        semesterStub = downloadDialog.findViewById(R.id.semesterStub);
        progressStub = downloadDialog.findViewById(R.id.progressStub);

        layoutQueue = new LinkedList<>();

        sharedPreferences = context.getSharedPreferences("tk.therealsuji.vtopchennai", Context.MODE_PRIVATE);
        myDatabase = context.openOrCreateDatabase("vtop", Context.MODE_PRIVATE, null);

        /*
            These views have to be re-inflated in case the download was terminated
         */
        isCaptchaInflated = false;
        isSemesterInflated = false;
        isProgressInflated = false;

        isCompressing = false;

        /*
            If the credentials aren't encrypted
         */
        if (username == null) {
            /*
                Get the non-encrypted credentials
             */
            username = sharedPreferences.getString("username", null);
            password = sharedPreferences.getString("password", null);

            /*
                Encrypt them
             */
            encryptedSharedPreferences.edit().putString("username", username).apply();
            encryptedSharedPreferences.edit().putString("password", password).apply();

            /*
                Remove the non-encrypted credentials
             */
            sharedPreferences.edit().remove("username").apply();
            sharedPreferences.edit().remove("password").apply();
        }

        pixelDensity = context.getResources().getDisplayMetrics().density;

        reloadPage();
    }

    /*
        Function to reload the page using javascript in case of an error.
        If something goes wrong, it'll log out and ask for the captcha again.
     */
    private void reloadPage() {
        if (terminateDownload) {
            return;
        }

        if (!isCompressing && !layoutQueue.isEmpty()) {
            compress();
        }

        isOpened = false;
        isInProgress = false;

        webView.clearCache(true);
        webView.clearHistory();
        CookieManager.getInstance().removeAllCookies(null);
        webView.loadUrl("http://vtopcc.vit.ac.in/vtop");
    }

    /*
        Setup the Captcha Layout
     */
    private void setupCaptcha() {
        if (isCaptchaInflated || terminateDownload) {
            return;
        }

        captchaStub.inflate();
        captcha = downloadDialog.findViewById(R.id.captchaCode);
        captchaLayout = downloadDialog.findViewById(R.id.captchaLayout);
        captchaView = downloadDialog.findViewById(R.id.captcha);
        isCaptchaInflated = true;
    }

    /*
        Setup the Semester Layout
     */
    private void setupSemester() {
        if (isSemesterInflated || terminateDownload) {
            return;
        }

        semesterStub.inflate();
        semesterLayout = downloadDialog.findViewById(R.id.semesterLayout);
        selectSemester = downloadDialog.findViewById(R.id.selectSemester);
        isSemesterInflated = true;
    }

    /*
        Setup the Progress Layout
     */
    private void setupProgress() {
        if (isProgressInflated || terminateDownload) {
            return;
        }

        progressStub.inflate();
        progressLayout = downloadDialog.findViewById(R.id.progressLayout);
        downloading = downloadDialog.findViewById(R.id.downloading);
        progressBar = downloadDialog.findViewById(R.id.progressBar);
        progressText = downloadDialog.findViewById(R.id.progressText);
        isProgressInflated = true;
    }

    /*
        Function to perform a smooth animation of expanding the layouts in the dialog
     */
    private void expand() {
        View view = layoutQueue.peek();

        if (terminateDownload || view == null) {
            return;
        }

        loading.setVisibility(View.INVISIBLE);

        view.measure(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        final int targetHeight = view.getMeasuredHeight();

        view.getLayoutParams().height = 0;
        view.setVisibility(View.INVISIBLE);
        view.setAlpha(0);

        ValueAnimator expand = ValueAnimator.ofInt(0, targetHeight);
        expand.setInterpolator(new AccelerateInterpolator());
        expand.setDuration(300);
        expand.addUpdateListener(animation -> {
            RelativeLayout.LayoutParams layoutParams = (RelativeLayout.LayoutParams) view.getLayoutParams();
            layoutParams.height = (int) (targetHeight * animation.getAnimatedFraction());
            view.setLayoutParams(layoutParams);
        });
        expand.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                RelativeLayout.LayoutParams layoutParams = (RelativeLayout.LayoutParams) view.getLayoutParams();
                layoutParams.height = RelativeLayout.LayoutParams.WRAP_CONTENT;
                view.setVisibility(View.VISIBLE);
                view.animate().alpha(1);
            }
        });
        expand.start();
    }

    /*
        Function to perform a smooth animation of compressing the layouts in the dialog
     */
    public void compress() {
        View view = layoutQueue.peek();

        if (terminateDownload || view == null) {
            return;
        }

        isCompressing = true;

        ViewPropertyAnimator fadeOut = view.animate().alpha(0);
        fadeOut.setListener(new AnimatorListenerAdapter() {
            /*
                Compress after the layout has faded out
             */
            @Override
            public void onAnimationEnd(Animator animation) {
                final int viewHeight = view.getMeasuredHeight();
                ValueAnimator compress = ValueAnimator.ofInt(viewHeight, 0);
                compress.setInterpolator(new AccelerateInterpolator());
                compress.setDuration(300);
                compress.addUpdateListener(animation1 -> {
                    RelativeLayout.LayoutParams layoutParams = (RelativeLayout.LayoutParams) view.getLayoutParams();
                    layoutParams.height = (int) (viewHeight * (1 - animation1.getAnimatedFraction()));
                    view.setLayoutParams(layoutParams);
                });
                compress.addListener(new AnimatorListenerAdapter() {
                    /*
                        Show the loading progress  bar after the layout has been compressed
                     */
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        view.setVisibility(View.GONE);

                        if (layoutQueue.size() == 1) {
                            layoutQueue.remove();
                            loading.setVisibility(View.VISIBLE);
                        } else {
                            layoutQueue.remove();
                            expand();
                        }

                        isCompressing = false;
                    }
                });
                compress.start();

                /*
                    Deleting the listener after it's been used
                 */
                fadeOut.setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                    }
                });
            }
        });
    }

    /*
        Function to open the sign in page
     */
    private void openSignIn() {
        if (terminateDownload) {
            return;
        }

        webView.evaluateJavascript("(function() {" +
                "var successFlag = false;" +
                "$.ajax({" +
                "    type: 'POST'," +
                "    url: 'vtopLogin'," +
                "    data: null," +
                "    async: false," +
                "    success: function(response) {" +
                "        if(response.search('___INTERNAL___RESPONSE___') == -1 && response.includes('VTOP Login')) {" +
                "            $('#page_outline').html(response);" +
                "            successFlag = true;" +
                "        }" +
                "    }" +
                "});" +
                "return successFlag;" +
                "})();", value -> {
            if (value.equals("true")) {
                getCaptchaType();
            } else {
                reloadPage();
            }
        });
    }

    /*
        Function to get the type of captcha (Default Captcha / Google reCaptcha)
     */
    private void getCaptchaType() {
        if (terminateDownload) {
            return;
        }

        isVerifyingCaptcha = false;

        webView.evaluateJavascript("(function() {" +
                "return x == 'local';" +
                "})();", isLocalCaptcha -> {
            /*
                isLocalCaptcha will be either true / false
                If true, the default captcha is being used else, Google's reCaptcha is being used
             */
            if (isLocalCaptcha.equals("null")) {
                error(102);
            } else if (isLocalCaptcha.equals("true")) {
                getCaptcha();
            } else {
                executeCaptcha();
            }
        });
    }

    /*
        For Default Captcha
        Function to get the captcha from the portal's sign in page and load it into the ImageView
     */
    private void getCaptcha() {
        if (terminateDownload) {
            return;
        }

        webView.evaluateJavascript("(function() {" +
                "var images = document.getElementsByTagName('img');" +
                "for(var i = 0; i < images.length; ++i) {" +
                "    if(images[i].alt.toLowerCase().includes('captcha')) {" +
                "        return images[i].src;" +
                "    }" +
                "}" +
                "})();", src -> {
            /*
                src will look like "data:image/png:base64, ContinuousGibberishText...." (including the quotes)
             */
            if (src.equals("null")) {
                error(103);
            } else {
                try {
                    src = src.substring(1, src.length() - 1).split(",")[1].trim();
                    byte[] decodedString = Base64.decode(src, Base64.DEFAULT);
                    Bitmap decodedImage = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);

                    String appearance = sharedPreferences.getString("appearance", "system");

                    setupCaptcha();     // Inflating the captcha layout

                    captcha.setImageBitmap(decodedImage);
                    if (appearance.equals("dark") || (appearance.equals("system") && (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)) {
                        captcha.setColorFilter(new ColorMatrixColorFilter(DARK));
                    }

                    captchaView.setText("");
                    layoutQueue.add(captchaLayout);
                    if (layoutQueue.size() == 1) {
                        expand();
                    }
                } catch (Exception e) {
                    if (ErrorHandler.isPreRelease) {
                        errorHandler.appendStackTrace(e);
                    }

                    error(104);
                }
            }
        });
    }

    /*
        For Google reCaptcha
        Function to override the default onSubmit function and execute the captcha
     */
    private void executeCaptcha() {
        if (terminateDownload) {
            return;
        }

        /*
            This will display the webView,
            however the user won't see anything other than the Captcha Challenge (If it needs to be solved)
         */
        renderCaptcha();

        /*
            Overriding the existing onSubmit function and attempting to render the reCaptcha
         */
        webView.evaluateJavascript("function onSubmit(token) {" +
                "    Android.signIn('g-recaptcha-response=' + token);" +
                "}" +
                "(function() {" +
                "var executeInterval = setInterval(function () {" +
                "    if (typeof grecaptcha != undefined) {" +
                "        grecaptcha.execute();" +
                "        clearInterval(executeInterval);" +
                "    }" +
                "}, 500);" +
                "})();", value -> {
        });
    }

    /*
        For Google reCaptcha
        Function to hide all elements and display only the Google reCaptcha (If it needs to be solved)
        TODO: There is still a minor design issue if the captcha has to be resubmitted by the user,
              also I'm not very comfortable using setInterval() here.
     */
    @JavascriptInterface
    public void renderCaptcha() {
        if (terminateDownload) {
            return;
        }

        ((Activity) context).runOnUiThread(() -> {
            DisplayMetrics displayMetrics = new DisplayMetrics();
            ((Activity) context).getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
            float width = displayMetrics.widthPixels;
            float scale = (width / pixelDensity - 80) / 300;

            if (scale > 1) {
                scale = 1;
            }

            webView.evaluateJavascript("(function() {" +
                    "var body = document.getElementsByTagName('body')[0];" +
                    "body.style.backgroundColor = 'transparent';" +
                    "var children = body.children;" +
                    "for (var i = 0; i < children.length - 1; ++i) {" +
                    "    children[i].style.display = 'none';" +
                    "}" +
                    "var captchaInterval = setInterval(function() {" +
                    "    var children = document.getElementsByTagName('body')[0].children;" +
                    "    var captcha = children[children.length - 1];" +
                    "    if (captcha.children[0] != null && captcha.children[1] != null) {" +
                    "        captcha.children[0].style.display = 'none';" +
                    "        captcha.children[1].style.transform = 'scale(" + scale + ")';" +
                    "        captcha.children[1].style.transformOrigin = '0 0';" +
                    "        clearInterval(captchaInterval);" +
                    "    }" +
                    "}, 500);" +
                    "})();", value -> {
                ViewGroup webViewParent = (ViewGroup) webView.getParent();
                if (webViewParent != null) {
                    webViewParent.removeView(webView);
                }

                LinearLayout.LayoutParams webViewParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                );
                webViewParams.setMarginStart((int) (40 * pixelDensity));
                webViewParams.setMarginEnd((int) (40 * pixelDensity));
                webViewParams.setMargins(0, (int) (40 * pixelDensity), 0, (int) (40 * pixelDensity));
                downloadDialog.addContentView(webView, webViewParams);
            });
        });
    }

    /*
        Function to sign in to the portal
     */
    @JavascriptInterface
    public void signIn(final String captcha) {
        if (terminateDownload) {
            return;
        }

        ((Activity) context).runOnUiThread(() -> {
            ViewGroup webViewParent = (ViewGroup) webView.getParent();
            if (webViewParent != null) {
                webViewParent.removeView(webView);
            }

            webView.evaluateJavascript("(function() {" +
                    "var credentials = 'uname=" + username + "&passwd=' + encodeURIComponent('" + password + "') + '&" + captcha + "';" +
                    "var successFlag;" +
                    "$.ajax({" +
                    "    type : 'POST'," +
                    "    url : 'doLogin'," +
                    "    data : credentials," +
                    "    async: false," +
                    "    success : function(response) {" +
                    "            if(response.search('___INTERNAL___RESPONSE___') == -1) {" +
                    "                $('#page_outline').html(response);" +
                    "            if(response.includes('authorizedIDX')) {" +
                    "                successFlag = true;" +
                    "            } else if(response.toLowerCase().includes('invalid captcha')) {" +
                    "                successFlag = 'Invalid Captcha';" +
                    "            } else if(response.toLowerCase().includes('invalid user id / password')) {" +
                    "                successFlag = 'Invalid User ID / Password';" +
                    "            } else if(response.toLowerCase().includes('user id not available')) {" +
                    "                successFlag = 'Invalid User ID';" +
                    "            } else if(response.toLowerCase().includes('your account is locked')) {" +
                    "                successFlag = 'Your Account is Locked';" +
                    "            }" +
                    "        }" +
                    "    }" +
                    "});" +
                    "return successFlag;" +
                    "})();", value -> {
                if (value.equals("null")) {
                    error(201);
                } else if (value.equals("true")) {
                    getSemesters();
                } else {
                    value = value.substring(1, value.length() - 1);
                    if (value.equals("Invalid Captcha")) {
                        getCaptchaType();
                    } else {
                        myDatabase.close();
                        downloadDialog.dismiss();

                        if (!value.equals("Your Account is Locked")) {
                            sharedPreferences.edit().putString("isLoggedIn", "false").apply();

                            if (!((Activity) context).getLocalClassName().equals("LoginActivity")) {
                                context.startActivity(new Intent(context, LoginActivity.class));
                                ((Activity) context).finish();
                            }
                        }
                    }

                    Toast.makeText(context, value, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /*
        Function to get a list of the semesters. These semesters are obtained from the Timetable page
     */
    private void getSemesters() {
        if (terminateDownload) {
            return;
        }

        webView.evaluateJavascript("(function() {" +
                "var data = 'verifyMenu=true&winImage=' + $('#winImage').val() + '&authorizedID=' + $('#authorizedIDX').val() + '&nocache=@(new Date().getTime())';" +
                "var obj;" +
                "$.ajax({" +
                "    type: 'POST'," +
                "    url : 'academics/common/StudentTimeTable'," +
                "    data : data," +
                "    async: false," +
                "    success: function(response) {" +
                "        if(response.toLowerCase().includes('time table')) {" +
                "            var doc = new DOMParser().parseFromString(response, 'text/html');" +
                "            var options = doc.getElementById('semesterSubId').getElementsByTagName('option');" +
                "            obj = {};" +
                "            for(var i = 0, j = 0; i < options.length; ++i, ++j) {" +
                "                if(options[i].innerText.toLowerCase().includes('choose') || options[i].innerText.toLowerCase().includes('select')) {" +
                "                    --j;" +
                "                    continue;" +
                "                }" +
                "                obj[j] = options[i].innerText;" +
                "            }" +
                "        }" +
                "    }" +
                "});" +
                "return obj;" +
                "})();", obj -> {
            /*
                obj is in the form of a JSON string like {"0": "Semester 1", "1": "Semester 2", "2": "Semester 3",...}
             */
            if (obj.equals("null")) {
                error(301);
            } else {
                try {
                    int index = 0;
                    boolean isIndexStored = false;
                    String storedSemester = sharedPreferences.getString("semester", "null");

                    setupSemester();        // Inflating the semester layout

                    JSONObject myObj = new JSONObject(obj);
                    List<String> options = new ArrayList<>();
                    for (int i = 0; i < myObj.length(); ++i) {
                        String semester = myObj.getString(Integer.toString(i));
                        options.add(semester);

                        if (!isIndexStored && semester.toLowerCase().equals(storedSemester)) {
                            index = i;
                            isIndexStored = true;
                        }
                    }
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(context, R.layout.style_spinner_selected, options);
                    adapter.setDropDownViewResource(R.layout.style_spinner);
                    selectSemester.setAdapter(adapter);
                    selectSemester.setSelection(index);

                    layoutQueue.add(semesterLayout);

                    if (layoutQueue.size() == 1) {
                        expand();
                    }
                } catch (Exception e) {
                    if (ErrorHandler.isPreRelease) {
                        errorHandler.appendStackTrace(e);
                    }

                    error(302);
                }
            }
        });
    }

    /*
        Function to get the semester ID from the Timetable page and store it locally for future use
        This is required to download the timetable, faculty, attendance, exam schedule, marks & grades
     */
    public void getSemesterID(String semester) {
        if (terminateDownload) {
            return;
        }

        webView.evaluateJavascript("(function() {" +
                "var data = 'verifyMenu=true&winImage=' + $('#winImage').val() + '&authorizedID=' + $('#authorizedIDX').val() + '&nocache=@(new Date().getTime())';" +
                "var semID;" +
                "$.ajax({" +
                "    type: 'POST'," +
                "    url : 'academics/common/StudentTimeTable'," +
                "    data : data," +
                "    async: false," +
                "    success: function(response) {" +
                "        if(response.toLowerCase().includes('time table')) {" +
                "            var doc = new DOMParser().parseFromString(response, 'text/html');" +
                "            var options = doc.getElementById('semesterSubId').getElementsByTagName('option');" +
                "            for(var i = 0; i < options.length; ++i) {" +
                "                if(options[i].innerText.toLowerCase().includes('" + semester + "')) {" +
                "                    semID = options[i].value;" +
                "                }" +
                "            }" +
                "        }" +
                "    }" +
                "});" +
                "return semID;" +
                "})();", semID -> {
            if (semID.equals("null")) {
                error(303);
            } else {
                semesterID = semID.substring(1, semID.length() - 1);
                downloadProfile();
            }
        });
    }

    /*
        Function to save the name of the user and his/her ID (register number) in SharedPreferences
        TBD: Saving the users profile picture
     */
    public void downloadProfile() {
        if (terminateDownload) {
            return;
        }

        setupProgress();    // Inflating the progress layout if it hasn't been inflated yet

        downloading.setText(context.getString(R.string.downloading_profile));
        if (!layoutQueue.contains(progressLayout)) {
            layoutQueue.add(progressLayout);

            if (layoutQueue.size() == 1) {
                expand();
            }
        }

        webView.evaluateJavascript("(function() {" +
                "var data = 'verifyMenu=true&winImage=' + $('#winImage').val() + '&authorizedID=' + $('#authorizedIDX').val() + '&nocache=@(new Date().getTime())';" +
                "var obj;" +
                "$.ajax({" +
                "    type: 'POST'," +
                "    url : 'studentsRecord/StudentProfileAllView'," +
                "    data : data," +
                "    async: false," +
                "    success: function(response) {" +
                "        if(response.toLowerCase().includes('personal information')) {" +
                "            var doc = new DOMParser().parseFromString(response, 'text/html');" +
                "            var cells = doc.getElementsByTagName('td');" +
                "            var name = '', id = '', j = 0;" +
                "            obj = {};" +
                "            for(var i = 0; i < cells.length && j < 2; ++i) {" +
                "                if(cells[i].innerText.toLowerCase().includes('name')) {" +
                "                    name = cells[++i].innerHTML;" +
                "                    ++j;" +
                "                }" +
                "                if(cells[i].innerText.toLowerCase().includes('register')) {" +
                "                    id = cells[++i].innerHTML;" +
                "                    ++j;" +
                "                }" +
                "            }" +
                "            obj['name'] = name;" +
                "            obj['id'] = id;" +
                "        }" +
                "    }" +
                "});" +
                "return obj;" +
                "})();", obj -> {
            /*
                obj is in the form of a JSON string like {"name": "JOHN DOE", "register": "20XYZ1987"}
             */
            if (obj.equals("null")) {
                error(401);
            } else {
                try {
                    JSONObject myObj = new JSONObject(obj);
                    sharedPreferences.edit().putString("name", myObj.getString("name")).apply();
                    sharedPreferences.edit().putString("id", myObj.getString("id")).apply();

                    lastDownload = 0;
                    updateProgress();

                    downloadTimetable();
                } catch (Exception e) {
                    if (ErrorHandler.isPreRelease) {
                        errorHandler.appendStackTrace(e);
                    }

                    error(402);
                }
            }
        });
    }

    /*
        Function to save the timetable in the SQLite database, and the credit score in SharedPreferences
     */
    public void downloadTimetable() {
        if (terminateDownload) {
            return;
        }

        setupProgress();    // Inflating the progress layout if it hasn't been inflated yet

        downloading.setText(context.getString(R.string.downloading_timetable));
        if (!layoutQueue.contains(progressLayout)) {
            layoutQueue.add(progressLayout);

            if (layoutQueue.size() == 1) {
                expand();
            }
        }

        webView.evaluateJavascript("(function() {" +
                "var data = 'semesterSubId=' + '" + semesterID + "' + '&authorizedID=' + $('#authorizedIDX').val();" +
                "var obj;" +
                "var successFlag = false;" +
                "$.ajax({" +
                "    type : 'POST'," +
                "    url : 'processViewTimeTable'," +
                "    data : data," +
                "    async: false," +
                "    success : function(response) {" +
                "        var doc = new DOMParser().parseFromString(response, 'text/html');" +
                "        var spans = doc.getElementById('getStudentDetails').getElementsByTagName('span');" +
                "        var credits = '0';" +
                "        obj = {};" +
                "        if(spans[0].innerText.toLowerCase().includes('no record(s) found')) {" +
                "           obj = false;" +
                "           return;" +
                "        }" +
                "        for(var i = spans.length-1; i > 0; --i) {" +
                "           if(spans[i].innerText.toLowerCase().includes('credits')) {" +
                "               credits = spans[i+1].innerText;" +
                "               break;" +
                "           }" +
                "        }" +
                "        obj['credits'] = credits;" +
                "        var cells = doc.getElementById('timeTableStyle').getElementsByTagName('td');" +
                "        var category = '';" +
                "        var timings = '';" +
                "        var postfix = '';" +
                "        var theory = {}, lab = {}, mon = {}, tue = {}, wed = {}, thu = {}, fri = {}, sat = {}, sun = {};" +
                "        var i = 0;" +
                "        for(var j = 0; j < cells.length; ++j) {" +
                "           if(cells[j].innerText.toLowerCase() == 'mon' || cells[j].innerText.toLowerCase() == 'tue' || cells[j].innerText.toLowerCase() == 'wed' || cells[j].innerText.toLowerCase() == 'thu' || cells[j].innerText.toLowerCase() == 'fri' || cells[j].innerText.toLowerCase() == 'sat' || cells[j].innerText.toLowerCase() == 'sun') {" +
                "               category = cells[j].innerText.toLowerCase();" +
                "               continue;" +
                "           }" +
                "           if(cells[j].innerText.toLowerCase() == 'theory' || cells[j].innerText.toLowerCase() == 'lab') {" +
                "               if(category == '' || category == 'theory' || category == 'lab') {" +
                "                   category = cells[j].innerText.toLowerCase();" +
                "               } else {" +
                "                   postfix = cells[j].innerText.toLowerCase();" +
                "               }" +
                "               i = 0;" +
                "               continue;" +
                "           }" +
                "           if(cells[j].innerText.toLowerCase() == 'start' || cells[j].innerText.toLowerCase() == 'end') {" +
                "               postfix = cells[j].innerText.toLowerCase();" +
                "               i = 0;" +
                "               continue;" +
                "           }" +
                "           subcat = i + postfix;" +
                "           if(category == 'theory') {" +
                "              theory[subcat] = cells[j].innerText.trim();" +
                "           } else if(category == 'lab') {" +
                "              lab[subcat] = cells[j].innerText.trim();" +
                "           } else if(category == 'mon') {" +
                "              if(cells[j].bgColor == '#CCFF33') {" +
                "                  mon[subcat] = cells[j].innerText.trim();" +
                "              }" +
                "           } else if(category == 'tue') {" +
                "              if(cells[j].bgColor == '#CCFF33') {" +
                "                  tue[subcat] = cells[j].innerText.trim();" +
                "              }" +
                "           } else if(category == 'wed') {" +
                "              if(cells[j].bgColor == '#CCFF33') {" +
                "                  wed[subcat] = cells[j].innerText.trim();" +
                "              }" +
                "           } else if(category == 'thu') {" +
                "              if(cells[j].bgColor == '#CCFF33') {" +
                "                  thu[subcat] = cells[j].innerText.trim();" +
                "              }" +
                "           } else if(category == 'fri') {" +
                "              if(cells[j].bgColor == '#CCFF33') {" +
                "                  fri[subcat] = cells[j].innerText.trim();" +
                "              }" +
                "           } else if(category == 'sat') {" +
                "             if(cells[j].bgColor == '#CCFF33') {" +
                "                 sat[subcat] = cells[j].innerText.trim();" +
                "              }" +
                "           } else if(category == 'sun') {" +
                "              if(cells[j].bgColor == '#CCFF33') {" +
                "                 sun[subcat] = cells[j].innerText.trim();" +
                "             }" +
                "          }" +
                "           ++i;" +
                "        }" +
                "        obj.theory = theory;" +
                "        obj.lab = lab;" +
                "        obj.mon = mon;" +
                "        obj.tue = tue;" +
                "        obj.wed = wed;" +
                "        obj.thu = thu;" +
                "        obj.fri = fri;" +
                "        obj.sat = sat;" +
                "        obj.sun = sun;" +
                "    }" +
                "});" +
                "return obj;" +
                "})();", obj -> {
            /*
                obj is in the form of a JSON string like {"credits": "19", "lab": {"0start": "08:00", "0end": "08:50",...}, "mon": {"0theory": "MAT1001",...}, ...}
             */
            if (obj.equals("null")) {
                error(501);
            } else if (obj.equals("false")) {
                new Thread(() -> {
                    myDatabase.execSQL("DROP TABLE IF EXISTS timetable_lab");
                    myDatabase.execSQL("CREATE TABLE timetable_lab (id INTEGER PRIMARY KEY, start_time VARCHAR, end_time VARCHAR, sun VARCHAR, mon VARCHAR, tue VARCHAR, wed VARCHAR, thu VARCHAR, fri VARCHAR, sat VARCHAR)");

                    myDatabase.execSQL("DROP TABLE IF EXISTS timetable_theory");
                    myDatabase.execSQL("CREATE TABLE timetable_theory (id INTEGER PRIMARY KEY, start_time VARCHAR, end_time VARCHAR, sun VARCHAR, mon VARCHAR, tue VARCHAR, wed VARCHAR, thu VARCHAR, fri VARCHAR, sat VARCHAR)");

                    sharedPreferences.edit().remove("credits").apply();

                    AlarmManager alarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
                    Intent notificationIntent = new Intent(context, NotificationReceiver.class);
                    for (int j = 0; j < sharedPreferences.getInt("alarmCount", 0); ++j) {
                        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, j, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
                        alarmManager.cancel(pendingIntent);
                    }

                    sharedPreferences.edit().remove("newTimetable").apply();
                    sharedPreferences.edit().remove("alarmCount").apply();

                    ((Activity) context).runOnUiThread(() -> {
                        updateProgress();
                        downloadCourses();
                    });
                }).start();
            } else {
                new Thread(() -> {
                    try {
                        JSONObject myObj = new JSONObject(obj);
                        String credits = "Credits: " + myObj.getString("credits");
                        sharedPreferences.edit().putString("credits", credits).apply();

                        myDatabase.execSQL("DROP TABLE IF EXISTS timetable_lab");
                        myDatabase.execSQL("CREATE TABLE timetable_lab (id INTEGER PRIMARY KEY, start_time VARCHAR, end_time VARCHAR, sun VARCHAR, mon VARCHAR, tue VARCHAR, wed VARCHAR, thu VARCHAR, fri VARCHAR, sat VARCHAR)");

                        myDatabase.execSQL("DROP TABLE IF EXISTS timetable_theory");
                        myDatabase.execSQL("CREATE TABLE timetable_theory (id INTEGER PRIMARY KEY, start_time VARCHAR, end_time VARCHAR, sun VARCHAR, mon VARCHAR, tue VARCHAR, wed VARCHAR, thu VARCHAR, fri VARCHAR, sat VARCHAR)");

                        JSONObject lab = new JSONObject(myObj.getString("lab"));
                        JSONObject theory = new JSONObject(myObj.getString("theory"));
                        JSONObject mon = new JSONObject(myObj.getString("mon"));
                        JSONObject tue = new JSONObject(myObj.getString("tue"));
                        JSONObject wed = new JSONObject(myObj.getString("wed"));
                        JSONObject thu = new JSONObject(myObj.getString("thu"));
                        JSONObject fri = new JSONObject(myObj.getString("fri"));
                        JSONObject sat = new JSONObject(myObj.getString("sat"));
                        JSONObject sun = new JSONObject(myObj.getString("sun"));

                        /*
                            This 12 hour check is because the genius developers at VIT decided it
                            would be a great idea to use both 12 hour and 24 hour formats together
                            because, who even cares...
                         */
                        SimpleDateFormat hour24 = new SimpleDateFormat("HH:mm", Locale.ENGLISH);
                        SimpleDateFormat hour12 = new SimpleDateFormat("h:mm a", Locale.ENGLISH);

                        Calendar c = Calendar.getInstance();
                        AlarmManager alarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
                        Intent notificationIntent = new Intent(context, NotificationReceiver.class);
                        SimpleDateFormat df = new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.ENGLISH);
                        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH);
                        Date today = dateFormat.parse(dateFormat.format(c.getTime()));
                        Date now = hour24.parse(hour24.format(c.getTime()));
                        int day = c.get(Calendar.DAY_OF_WEEK) - 1;

                        JSONObject[] days = {sun, mon, tue, wed, thu, fri, sat};

                        int alarmCount = 0;

                        for (int i = 0; i < lab.length() / 2 && i < theory.length() / 2; ++i) {
                            String start_time_lab = lab.getString(i + "start");
                            if (start_time_lab.equalsIgnoreCase("lunch")) {
                                continue;
                            }
                            String end_time_lab = lab.getString(i + "end");
                            String start_time_theory = theory.getString(i + "start");
                            String end_time_theory = theory.getString(i + "end");

                            /*
                                Formatting the time in-case it is given in 12-hour format
                             */
                            String[] times = {start_time_lab, end_time_lab, start_time_theory, end_time_theory};
                            for (int j = 0; j < times.length; ++j) {
                                try {
                                    Date time = hour24.parse(times[j]);
                                    Date hourStart = hour24.parse("08:00");

                                    if (time != null && time.before(hourStart)) {
                                        time = hour12.parse(times[j] + " PM");
                                        if (time != null) {
                                            times[j] = hour24.format(time);
                                        }
                                    }
                                } catch (Exception ignored) {
                                }
                            }

                            start_time_lab = times[0];
                            end_time_lab = times[1];
                            start_time_theory = times[2];
                            end_time_theory = times[3];

                            /*
                                Inserting periods
                             */
                            String[] labPeriods = new String[7];
                            String[] theoryPeriods = new String[7];

                            for (int j = 0; j < 7; ++j) {
                                labPeriods[j] = "null";
                                /*
                                    Inserting Lab Periods
                                 */
                                if (days[j].has(i + "lab")) {
                                    labPeriods[j] = days[j].getString(i + "lab");

                                    if (j == day) {
                                        Date current = hour24.parse(start_time_lab);
                                        assert current != null;

                                        if (current.after(now) || current.equals(now)) {
                                            assert today != null;
                                            c.setTime(today);
                                        } else {
                                            assert today != null;
                                            c.setTime(today);
                                            c.add(Calendar.DATE, 7);
                                        }
                                    } else if (j > day) {
                                        assert today != null;
                                        c.setTime(today);
                                        c.add(Calendar.DATE, j - day);
                                    } else {
                                        assert today != null;
                                        c.setTime(today);
                                        c.add(Calendar.DATE, 7 - day + j);
                                    }

                                    Date date = df.parse(dateFormat.format(c.getTime()) + " " + start_time_lab);

                                    assert date != null;
                                    c.setTime(date);
                                    PendingIntent pendingIntent = PendingIntent.getBroadcast(context, alarmCount++, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
                                    alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), AlarmManager.INTERVAL_DAY * 7, pendingIntent);

                                    c.add(Calendar.MINUTE, -30);
                                    pendingIntent = PendingIntent.getBroadcast(context, alarmCount++, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
                                    alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), AlarmManager.INTERVAL_DAY * 7, pendingIntent);
                                }

                                theoryPeriods[j] = "null";
                                /*
                                    Inserting Theory periods
                                 */
                                if (days[j].has(i + "theory")) {
                                    theoryPeriods[j] = days[j].getString(i + "theory");

                                    if (j == day) {
                                        Date current = hour24.parse(start_time_theory);
                                        assert current != null;

                                        if (current.after(now) || current.equals(now)) {
                                            assert today != null;
                                            c.setTime(today);
                                        } else {
                                            assert today != null;
                                            c.setTime(today);
                                            c.add(Calendar.DATE, 7);
                                        }
                                    } else if (j > day) {
                                        assert today != null;
                                        c.setTime(today);
                                        c.add(Calendar.DATE, j - day);
                                    } else {
                                        assert today != null;
                                        c.setTime(today);
                                        c.add(Calendar.DATE, 7 - day + j);
                                    }

                                    Date date = df.parse(dateFormat.format(c.getTime()) + " " + start_time_theory);

                                    assert date != null;
                                    c.setTime(date);
                                    PendingIntent pendingIntent = PendingIntent.getBroadcast(context, alarmCount++, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
                                    alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), AlarmManager.INTERVAL_DAY * 7, pendingIntent);

                                    c.add(Calendar.MINUTE, -30);
                                    pendingIntent = PendingIntent.getBroadcast(context, alarmCount++, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
                                    alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), AlarmManager.INTERVAL_DAY * 7, pendingIntent);
                                }
                            }

                            myDatabase.execSQL("INSERT INTO timetable_lab (start_time, end_time, sun, mon, tue, wed, thu, fri, sat) VALUES ('" + start_time_lab + "', '" + end_time_lab + "', '" + labPeriods[0] + "', '" + labPeriods[1] + "', '" + labPeriods[2] + "', '" + labPeriods[3] + "', '" + labPeriods[4] + "', '" + labPeriods[5] + "', '" + labPeriods[6] + "')");
                            myDatabase.execSQL("INSERT INTO timetable_theory (start_time, end_time, sun, mon, tue, wed, thu, fri, sat) VALUES ('" + start_time_theory + "', '" + end_time_theory + "', '" + theoryPeriods[0] + "', '" + theoryPeriods[1] + "', '" + theoryPeriods[2] + "', '" + theoryPeriods[3] + "', '" + theoryPeriods[4] + "', '" + theoryPeriods[5] + "', '" + theoryPeriods[6] + "')");
                        }

                        for (int i = alarmCount; i < sharedPreferences.getInt("alarmCount", 0); ++i) {
                            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, i, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
                            alarmManager.cancel(pendingIntent);
                        }

                        sharedPreferences.edit().putInt("alarmCount", alarmCount).apply();

                        ((Activity) context).runOnUiThread(() -> {
                            updateProgress();
                            downloadCourses();
                        });
                    } catch (Exception e) {
                        if (ErrorHandler.isPreRelease) {
                            errorHandler.appendStackTrace(e);
                        }

                        error(502);
                    }
                }).start();
            }
        });
    }

    /*
        Function to store the course info from the timetable page in the SQLite database.
     */
    public void downloadCourses() {
        if (terminateDownload) {
            return;
        }

        setupProgress();    // Inflating the progress layout if it hasn't been inflated yet

        downloading.setText(context.getString(R.string.downloading_courses));
        if (!layoutQueue.contains(progressLayout)) {
            layoutQueue.add(progressLayout);

            if (layoutQueue.size() == 1) {
                expand();
            }
        }

        webView.evaluateJavascript("(function() {" +
                "var data = 'semesterSubId=' + '" + semesterID + "' + '&authorizedID=' + $('#authorizedIDX').val();" +
                "var obj;" +
                "var successFlag = false;" +
                "$.ajax({" +
                "    type : 'POST'," +
                "    url : 'processViewTimeTable'," +
                "    data : data," +
                "    async: false," +
                "    success : function(response) {" +
                "        var doc = new DOMParser().parseFromString(response, 'text/html');" +
                "        obj = {};" +
                "        if (!doc.getElementById('studentDetailsList')) {" +
                "            obj = false;" +
                "            return;" +
                "        }" +
                "        var division = doc.getElementById('studentDetailsList').getElementsByTagName('table')[0]; " +
                "        var correction = 0;" +
                "        if (typeof division.getElementsByTagName('tr')[0].getElementsByTagName('td')[0] != 'undefined') {" +
                "            correction = 1;" +      // +1 is a correction due to an extra 'td' element at the top
                "        }" +
                "        var heads = division.getElementsByTagName('th');" +
                "        var courseIndex, slotIndex, facultyIndex, flag = 0;" +
                "        var columns = heads.length;" +
                "        for(var i = 0; i < columns; ++i) {" +
                "           var heading = heads[i].innerText.toLowerCase();" +
                "           if(heading == 'course') {" +
                "               courseIndex = i + correction;" +
                "               ++flag;" +
                "           }" +
                "           if(heading.includes('slot') && heading.includes('venue')) {" +
                "               slotIndex = i + correction;" +
                "               ++flag;" +
                "           }" +
                "           if(heading.includes('faculty') && heading.includes('details')) {" +
                "               facultyIndex = i + correction;" +
                "               ++flag;" +
                "           }" +
                "           if(flag >= 3) {" +
                "               break;" +
                "           }" +
                "        }" +
                "        var cells = division.getElementsByTagName('td');" +
                "        for(var i = 0; courseIndex < cells.length && slotIndex < cells.length && facultyIndex < cells.length; ++i) {" +
                "            var temp = {};" +
                "            temp['course'] = cells[courseIndex].innerText.trim().replace(/\\t/g,'').replace(/\\n/g,' ');;" +
                "            temp['slot'] = cells[slotIndex].innerText.replace(/\\t/g,'').replace(/\\n/g,'').trim();" +
                "            temp['faculty'] = cells[facultyIndex].innerText.replace(/\\t/g,'').replace(/\\n/g,'').trim();" +
                "            obj[i] = temp;" +
                "            courseIndex += columns;" +
                "            slotIndex += columns;" +
                "            facultyIndex += columns;" +
                "        }" +
                "    }" +
                "});" +
                "return obj;" +
                "})();", obj -> {
            /*
                obj is in the form of a JSON string like {"0": {"course": "MAT1001", "faculty": "JAMES VERTIGO"},...}
             */
            if (obj.equals("null")) {
                error(601);
            } else if (obj.equals("false")) {
                new Thread(() -> {
                    myDatabase.execSQL("DROP TABLE IF EXISTS faculty"); // Old data
                    myDatabase.execSQL("DROP TABLE IF EXISTS courses");
                    myDatabase.execSQL("CREATE TABLE courses (id INTEGER PRIMARY KEY, course_code VARCHAR, course VARCHAR, course_type VARCHAR, slot , venue VARCHAR, faculty VARCHAR, school VARCHAR)");

                    ((Activity) context).runOnUiThread(() -> {
                        updateProgress();
                        downloadProctor();
                    });

                    sharedPreferences.edit().remove("newFaculty").apply();  // Old data
                    sharedPreferences.edit().remove("newCourses").apply();
                }).start();
            } else {
                new Thread(() -> {
                    try {
                        JSONObject myObj = new JSONObject(obj);

                        myDatabase.execSQL("DROP TABLE IF EXISTS faculty"); // Old data
                        myDatabase.execSQL("DROP TABLE IF EXISTS courses");
                        myDatabase.execSQL("CREATE TABLE courses (id INTEGER PRIMARY KEY, course_code VARCHAR, course VARCHAR, course_type VARCHAR, slot , venue VARCHAR, faculty VARCHAR, school VARCHAR)");

                        for (int i = 0; i < myObj.length(); ++i) {
                            JSONObject tempObj = new JSONObject(myObj.getString(Integer.toString(i)));
                            String[] courseString = tempObj.getString("course").split("-");
                            String[] slotString = tempObj.getString("slot").split("-");
                            String[] facultyString = tempObj.getString("faculty").split("-");

                            String courseCode = courseString[0].trim();
                            StringBuilder course = new StringBuilder(courseString[1].trim());
                            for (int j = 2; j < courseString.length - 1; ++j) {
                                course.append(" - ").append(courseString[j].trim());
                            }
                            String courseType = courseString[courseString.length - 1].trim();

                            if (courseType.contains("(")) {
                                course.append(" - ").append(courseString[courseString.length - 1].trim());
                                course = new StringBuilder(course.substring(0, course.indexOf("(")));
                                courseType = courseType.substring(courseType.indexOf("(") + 1, courseType.indexOf(")")).trim();
                            }

                            String slot = slotString[0].trim().replaceAll("\\+", " + ");
                            StringBuilder venue = new StringBuilder(slotString[1].trim());
                            for (int j = 2; j < slotString.length; ++j) {
                                venue.append(" - ").append(slotString[j].trim());
                            }

                            String faculty = facultyString[0].trim();
                            String school = facultyString[1].trim();

                            myDatabase.execSQL("INSERT INTO courses (course_code, course, course_type, slot, venue, faculty, school) VALUES('" + courseCode + "','" + course.toString() + "', '" + courseType + "','" + slot + "', '" + venue.toString() + "','" + faculty + "','" + school + "')");
                        }

                        sharedPreferences.edit().remove("newFaculty").apply();  // Old data

                        ((Activity) context).runOnUiThread(() -> {
                            updateProgress();
                            downloadProctor();
                        });
                    } catch (Exception e) {
                        if (ErrorHandler.isPreRelease) {
                            errorHandler.appendStackTrace(e);
                        }

                        error(602);
                    }
                }).start();
            }
        });
    }

    /*
        Function to store the proctor info (1 / 2 - Staff info) in the SQLite database.
     */
    public void downloadProctor() {
        if (terminateDownload) {
            return;
        }

        setupProgress();    // Inflating the progress layout if it hasn't been inflated yet

        downloading.setText(context.getString(R.string.downloading_staff));
        if (!layoutQueue.contains(progressLayout)) {
            layoutQueue.add(progressLayout);

            if (layoutQueue.size() == 1) {
                expand();
            }
        }

        webView.evaluateJavascript("(function() {" +
                "var data = 'verifyMenu=true&winImage=' + $('#winImage').val() + '&authorizedID=' + $('#authorizedIDX').val() + '&nocache=@(new Date().getTime())';" +
                "var obj;" +
                "$.ajax({" +
                "    type: 'POST'," +
                "    url : 'proctor/viewProctorDetails'," +
                "    data : data," +
                "    async: false," +
                "    success: function(response) {" +
                "        var doc = new DOMParser().parseFromString(response, 'text/html');" +
                "        obj = {};" +
                "        if(!doc.getElementById('showDetails').getElementsByTagName('td')) {" +
                "        obj = false;" +
                "        return;" +
                "    }" +
                "    var cells = doc.getElementById('showDetails').getElementsByTagName('td');" +
                "    for(var i = 0; i < cells.length; ++i) {" +
                "        if(cells[i].innerHTML.includes('img')) {" +
                "            continue;" +
                "        }" +
                "        var key = cells[i].innerText.trim();" +
                "        var value = cells[++i].innerText.trim();" +
                "        var prefix = i;" +
                "        if (i < 10) {" +
                "            prefix = '0' + i;" +
                "        }" +
                "        obj[prefix + key] = value;" +
                "    }" +
                "}" +
                "});" +
                "return obj;" +
                "})();", obj -> {
            /*
                obj is in the form of a JSON string like {"00Faculty Name": "Jack Ryan", "01Email ID": "jack@cia.gov.us",...}
             */
            if (obj.equals("null")) {
                error(701);
            } else if (obj.equals("false")) {
                new Thread(() -> {
                    myDatabase.execSQL("DROP TABLE IF EXISTS proctor");
                    myDatabase.execSQL("CREATE TABLE proctor (id INTEGER PRIMARY KEY, column1 VARCHAR, column2 VARCHAR)");

                    ((Activity) context).runOnUiThread(() -> {
                        updateProgress();
                        downloadDeanHOD();
                    });
                }).start();
            } else {
                new Thread(() -> {
                    try {
                        JSONObject myObj = new JSONObject(obj);

                        myDatabase.execSQL("DROP TABLE IF EXISTS proctor");
                        myDatabase.execSQL("CREATE TABLE proctor (id INTEGER PRIMARY KEY, column1 VARCHAR, column2 VARCHAR)");

                        Iterator<?> keys = myObj.keys();

                        while (keys.hasNext()) {
                            String key = (String) keys.next();
                            String value = myObj.getString(key);

                            key = key.substring(2);

                            myDatabase.execSQL("INSERT INTO proctor (column1, column2) VALUES('" + key + "', '" + value + "')");
                        }

                        ((Activity) context).runOnUiThread(() -> {
                            updateProgress();
                            downloadDeanHOD();
                        });
                    } catch (Exception e) {
                        if (ErrorHandler.isPreRelease) {
                            errorHandler.appendStackTrace(e);
                        }

                        error(702);
                    }
                }).start();
            }
        });
    }

    /*
        Function to store the HOD & Dean info (2 / 2 - Staff info) in the SQLite database.
     */
    public void downloadDeanHOD() {
        if (terminateDownload) {
            return;
        }

        setupProgress();    // Inflating the progress layout if it hasn't been inflated yet

        downloading.setText(context.getString(R.string.downloading_staff));
        if (!layoutQueue.contains(progressLayout)) {
            layoutQueue.add(progressLayout);

            if (layoutQueue.size() == 1) {
                expand();
            }
        }
        webView.evaluateJavascript("(function() {" +
                "var data = 'verifyMenu=true&winImage=' + $('#winImage').val() + '&authorizedID=' + $('#authorizedIDX').val() + '&nocache=@(new Date().getTime())';" +
                "var obj;" +
                "$.ajax({" +
                "    type: 'POST'," +
                "    url : 'hrms/viewHodDeanDetails'," +
                "    data : data," +
                "    async: false," +
                "    success: function(response) {" +
                "        var doc = new DOMParser().parseFromString(response, 'text/html');" +
                "        obj = {};" +
                "        var tables = doc.getElementsByTagName('table');" +
                "        if (tables.length == 0) {" +
                "            obj = false;" +
                "            return;" +
                "        }" +
                "        var headers = doc.getElementsByTagName('h3');" +
                "        var dean = [], hod = [];" +
                "        for (var i = 0; i < tables.length; ++i) {" +
                "            var cells = tables[i].getElementsByTagName('td');" +
                "            var isDean = headers[i].innerText.toLowerCase().includes('dean');" +
                "            var count = 0;" +
                "            for (var j = 0; j < cells.length; ++j) {" +
                "                if (cells[j].innerHTML.includes('img')) {" +
                "                    continue;" +
                "                }" +
                "                var key = cells[j].innerText.trim();" +
                "                var value = cells[++j].innerText.trim();" +
                "                var column = {" +
                "                    key: key," +
                "                    value: value" +
                "                };" +
                "                if (isDean) {" +
                "                    dean.push(column);" +
                "                } else {" +
                "                    hod.push(column);" +
                "                }" +
                "            }" +
                "        }" +
                "        obj = {" +
                "            dean: dean," +
                "            hod: hod" +
                "        };" +
                "   }" +
                "});" +
                "return obj;" +
                "})();", obj -> {
            /*
             * Example of response object
             *
             * {
             *     "dean": [
             *         {
             *             "key": "Name of Faculty",
             *             "value": "John Doe"
             *         },
             *         {
             *             "key": "Designation",
             *             "value": "Associate Professor"
             *         }
             *     ],
             *     "hod": [
             *         {
             *             "key": "Name of Faculty",
             *             "value": "John Doe"
             *         },
             *         {
             *             "key": "Designation",
             *             "value": "Associate Professor"
             *         }
             *     ],
             * }
             */
            if (obj.equals("null")) {
                error(703);
            } else if (obj.equals("false")) {
                new Thread(() -> {
                    myDatabase.execSQL("DROP TABLE IF EXISTS dean");
                    myDatabase.execSQL("CREATE TABLE dean (id INTEGER PRIMARY KEY, column1 VARCHAR, column2 VARCHAR)");

                    myDatabase.execSQL("DROP TABLE IF EXISTS hod");
                    myDatabase.execSQL("CREATE TABLE hod (id INTEGER PRIMARY KEY, column1 VARCHAR, column2 VARCHAR)");

                    ((Activity) context).runOnUiThread(() -> {
                        updateProgress();
                        downloadAttendance();
                    });
                }).start();
            } else {
                new Thread(() -> {
                    try {
                        JSONObject myObj = new JSONObject(obj);
                        JSONArray dean = myObj.getJSONArray("dean");
                        JSONArray hod = myObj.getJSONArray("hod");

                        myDatabase.execSQL("DROP TABLE IF EXISTS dean");
                        myDatabase.execSQL("CREATE TABLE dean (id INTEGER PRIMARY KEY, column1 VARCHAR, column2 VARCHAR)");

                        myDatabase.execSQL("DROP TABLE IF EXISTS hod");
                        myDatabase.execSQL("CREATE TABLE hod (id INTEGER PRIMARY KEY, column1 VARCHAR, column2 VARCHAR)");

                        Iterator<?> keys = dean.keys();

                        for (int i = 0; i < dean.length(); ++i) {
                            String key = dean.getJSONObject(i).getString("key");
                            String value = dean.getJSONObject(i).getString("value");

                            myDatabase.execSQL("INSERT INTO dean (column1, column2) VALUES('" + key + "', '" + value + "')");
                        }

                        for (int i = 0; i < hod.length(); ++i) {
                            String key = hod.getJSONObject(i).getString("key");
                            String value = hod.getJSONObject(i).getString("value");

                            myDatabase.execSQL("INSERT INTO hod (column1, column2) VALUES('" + key + "', '" + value + "')");
                        }

                        ((Activity) context).runOnUiThread(() -> {
                            updateProgress();
                            downloadAttendance();
                        });
                    } catch (Exception e) {
                        if (ErrorHandler.isPreRelease) {
                            errorHandler.appendStackTrace(e);
                        }

                        error(704);
                    }
                }).start();
            }
        });
    }

    /*
        Function to store the attendance in the SQLite database.
     */
    public void downloadAttendance() {
        if (terminateDownload) {
            return;
        }

        setupProgress();    // Inflating the progress layout if it hasn't been inflated yet

        downloading.setText(context.getString(R.string.downloading_attendance));
        if (!layoutQueue.contains(progressLayout)) {
            layoutQueue.add(progressLayout);

            if (layoutQueue.size() == 1) {
                expand();
            }
        }

        webView.evaluateJavascript("(function() {" +
                "var data = 'semesterSubId=' + '" + semesterID + "' + '&authorizedID=' + $('#authorizedIDX').val();" +
                "var obj;" +
                "$.ajax({" +
                "    type : 'POST'," +
                "    url : 'processViewStudentAttendance'," +
                "    data : data," +
                "    async: false," +
                "    success : function(response) {" +
                "        var doc = new DOMParser().parseFromString(response, 'text/html');" +
                "        var division = doc.getElementById('getStudentDetails');" +
                "        obj = {};" +
                "        if(division.getElementsByTagName('td').length == 1) {" +
                "            obj = false;" +
                "        } else {" +
                "            var heads = division.getElementsByTagName('th');" +
                "            var courseIndex, typeIndex, attendedIndex, totalIndex, percentIndex, flag = 0;" +
                "            var columns = heads.length;" +
                "            for(var i = 0; i < columns; ++i) {" +
                "                var heading = heads[i].innerText.toLowerCase();" +
                "                if(heading.includes('course') &&  heading.includes('code')) {" +
                "                    courseIndex = i;" +
                "                    ++flag;" +
                "                }" +
                "                if(heading.includes('course') && heading.includes('type')) {" +
                "                    typeIndex = i;" +
                "                    ++flag;" +
                "                }" +
                "                if(heading.includes('attended')) {" +
                "                    attendedIndex = i;" +
                "                    ++flag;" +
                "                }" +
                "                if(heading.includes('total')) {" +
                "                    totalIndex = i;" +
                "                    ++flag;" +
                "                }" +
                "                if(heading.includes('percentage')) {" +
                "                    percentIndex = i;" +
                "                    ++flag;" +
                "                }" +
                "                if(flag >= 5) {" +
                "                    break;" +
                "                }" +
                "            }" +
                "            var cells = division.getElementsByTagName('td');" +
                "            for(var i = 0; courseIndex < cells.length && typeIndex < cells.length  && attendedIndex < cells.length && totalIndex < cells.length && percentIndex < cells.length; ++i) {" +
                "                var temp = {};" +
                "                temp['course'] = cells[courseIndex].innerText.trim();" +
                "                temp['type'] = cells[typeIndex].innerText.trim();" +
                "                temp['attended'] = cells[attendedIndex].innerText.trim();" +
                "                temp['total'] = cells[totalIndex].innerText.trim();" +
                "                temp['percent'] = cells[percentIndex].innerText.trim();" +
                "                obj[i] = temp;" +
                "                courseIndex += columns;" +
                "                attendedIndex += columns;" +
                "                totalIndex += columns;" +
                "                typeIndex += columns;" +
                "                percentIndex += columns;" +
                "            }" +
                "        }" +
                "    }" +
                "});" +
                "return obj;" +
                "})();", obj -> {
            /*
                obj is in the form of a JSON string like {"0": {"course": "MAT1001", "type": "Embedded Theory",...},...}
             */
            if (obj.equals("null")) {
                error(801);
            } else if (obj.equals("false")) {
                new Thread(() -> {
                    myDatabase.execSQL("DROP TABLE IF EXISTS attendance");
                    myDatabase.execSQL("CREATE TABLE attendance (id INTEGER PRIMARY KEY, course VARCHAR, type VARCHAR, attended VARCHAR, total VARCHAR, percent VARCHAR)");

                    ((Activity) context).runOnUiThread(() -> {
                        updateProgress();
                        downloadExams();
                    });

                    sharedPreferences.edit().remove("failedAttendance").apply();
                }).start();
            } else {
                new Thread(() -> {
                    try {
                        JSONObject myObj = new JSONObject(obj);

                        myDatabase.execSQL("DROP TABLE IF EXISTS attendance");
                        myDatabase.execSQL("CREATE TABLE attendance (id INTEGER PRIMARY KEY, course VARCHAR, type VARCHAR, attended VARCHAR, total VARCHAR, percent VARCHAR)");

                        sharedPreferences.edit().remove("failedAttendance").apply();

                        for (int i = 0; i < myObj.length(); ++i) {
                            JSONObject tempObj = new JSONObject(myObj.getString(Integer.toString(i)));
                            String course = tempObj.getString("course");
                            String type = tempObj.getString("type");
                            String attended = tempObj.getString("attended");
                            String total = tempObj.getString("total");
                            String percent = tempObj.getString("percent");

                            myDatabase.execSQL("INSERT INTO attendance (course, type, attended, total, percent) VALUES('" + course + "', '" + type + "', '" + attended + "', '" + total + "', '" + percent + "')");

                            if (Integer.parseInt(percent) <= 75) {
                                sharedPreferences.edit().putBoolean("failedAttendance", true).apply();
                            }
                        }

                        ((Activity) context).runOnUiThread(() -> {
                            updateProgress();
                            downloadExams();
                        });
                    } catch (Exception e) {
                        if (ErrorHandler.isPreRelease) {
                            errorHandler.appendStackTrace(e);
                        }

                        error(802);
                    }
                }).start();
            }
        });
    }

    /*
        Function to store the exam schedule in the SQLite database.
     */
    public void downloadExams() {
        if (terminateDownload) {
            return;
        }

        setupProgress();    // Inflating the progress layout if it hasn't been inflated yet

        downloading.setText(context.getString(R.string.downloading_exams));
        if (!layoutQueue.contains(progressLayout)) {
            layoutQueue.add(progressLayout);

            if (layoutQueue.size() == 1) {
                expand();
            }
        }

        webView.evaluateJavascript("(function() {" +
                "var data = 'semesterSubId=' + '" + semesterID + "' + '&authorizedID=' + $('#authorizedIDX').val();" +
                "var obj;" +
                "$.ajax({" +
                "    type: 'POST'," +
                "    url : 'examinations/doSearchExamScheduleForStudent'," +
                "    data : data," +
                "    async: false," +
                "    success: function(response) {" +
                "        obj = {};" +
                "        if(response.toLowerCase().includes('not found')) {" +
                "            obj = false;" +
                "        } else {" +
                "            var doc = new DOMParser().parseFromString(response, 'text/html');" +
                "            var courseIndex, titleIndex, slotIndex, dateIndex, reportingIndex, timingIndex, venueIndex, locationIndex, seatIndex, flag = 0;" +
                "            var columns = doc.getElementsByTagName('tr')[0].getElementsByTagName('td');" +
                "            for (var i = 0; i < columns.length; ++i) {" +
                "                var heading = columns[i].innerText.toLowerCase();" +
                "                if (heading.includes('code')) {" +
                "                    courseIndex = i;" +
                "                    ++flag;" +
                "                } else if (heading.includes('title')) {" +
                "                    titleIndex = i;" +
                "                    ++flag;" +
                "                } else if (heading.includes('slot')) {" +
                "                    slotIndex = i;" +
                "                    ++flag;" +
                "                } else if (heading.includes('date')) {" +
                "                    dateIndex = i;" +
                "                    ++flag;" +
                "                } else if (heading.includes('reporting')) {" +
                "                    reportingIndex = i;" +
                "                    ++flag;" +
                "                } else if (heading.includes('exam') && heading.includes('time')) {" +
                "                    timingIndex = i;" +
                "                    ++flag;" +
                "                } else if (heading.includes('venue')) {" +
                "                    venueIndex = i;" +
                "                    ++flag;" +
                "                } else if (heading.includes('location')) {" +
                "                    locationIndex = i;" +
                "                    ++flag;" +
                "                } else if (heading.includes('seat') && heading.includes('no.')) {" +
                "                    seatIndex = i;" +
                "                    ++flag;" +
                "                }" +
                "                if (flag >= 9) {" +
                "                    break;" +
                "                }" +
                "            }" +
                "            var exam = '', cells = doc.getElementsByTagName('td'), remainder = 0, record = -1;" +
                "            for (var i = columns.length; i < cells.length; ++i) {" +
                "                if (flag >= 9) {" +
                "                    flag = 0;" +
                "                    ++record;" +
                "                }" +
                "                if (cells[i].colSpan > 1) {" +
                "                    exam = cells[i].innerText.trim();" +
                "                    ++remainder;" +
                "                    record = 0;" +
                "                    continue;" +
                "                }" +
                "                if (typeof obj[exam] == 'undefined') {" +
                "                    obj[exam] = {};" +
                "                }" +
                "                var index = (i - remainder) % columns.length;" +
                "                if (index == courseIndex) {" +
                "                    obj[exam]['course' + record] = cells[i].innerText.trim();" +
                "                    ++flag;" +
                "                } else if (index == titleIndex) {" +
                "                    obj[exam]['title' + record] = cells[i].innerText.trim();" +
                "                    ++flag;" +
                "                } else if (index == slotIndex) {" +
                "                    obj[exam]['slot' + record] = cells[i].innerText.trim();" +
                "                    ++flag;" +
                "                } else if (index == dateIndex) {" +
                "                    obj[exam]['date' + record] = cells[i].innerText.trim().toUpperCase();" +
                "                    ++flag;" +
                "                } else if (index == reportingIndex) {" +
                "                    obj[exam]['reporting' + record] = cells[i].innerText.trim();" +
                "                    ++flag;" +
                "                } else if (index == timingIndex) {" +
                "                    var timings = cells[i].innerText.split('-');" +
                "                    if (timings.length == 1) {" +
                "                        obj[exam]['start' + record] = '';" +
                "                        obj[exam]['end' + record] = '';" +
                "                    } else {" +
                "                        obj[exam]['start' + record] = timings[0].trim();" +
                "                        obj[exam]['end' + record] = timings[1].trim();" +
                "                    }" +
                "                    ++flag;" +
                "                } else if (index == venueIndex) {" +
                "                    obj[exam]['venue' + record] = cells[i].innerText.trim();" +
                "                    ++flag;" +
                "                } else if (index == locationIndex) {" +
                "                    obj[exam]['location' + record] = cells[i].innerText.trim();" +
                "                    ++flag;" +
                "                } else if (index == seatIndex) {" +
                "                    obj[exam]['seat' + record] = cells[i].innerText.trim();" +
                "                    ++flag;" +
                "                }" +
                "            }" +
                "        }" +
                "    }" +
                "});" +
                "return obj;" +
                "})();", obj -> {
            /*
                obj is in the form of a JSON string like {"Mid Term": {"course": "MAT1001", "date": "04-Jan-1976",...},...}
             */
            if (obj.equals("null")) {
                error(901);
            } else if (obj.equals("false")) {
                new Thread(() -> {
                    myDatabase.execSQL("DROP TABLE IF EXISTS exams");
                    myDatabase.execSQL("CREATE TABLE exams (id INTEGER PRIMARY KEY, exam VARCHAR, course VARCHAR, title VARCHAR, slot VARCHAR, date VARCHAR, reporting VARCHAR, start_time VARCHAR, end_time VARCHAR, venue VARCHAR, location VARCHAR, seat VARCHAR)");

                    ((Activity) context).runOnUiThread(() -> {
                        updateProgress();
                        downloadMarks();
                    });

                    sharedPreferences.edit().remove("newExams").apply();
                    sharedPreferences.edit().remove("examsCount").apply();
                }).start();
            } else {
                new Thread(() -> {
                    try {
                        JSONObject myObj = new JSONObject(obj);

                        myDatabase.execSQL("DROP TABLE IF EXISTS exams");
                        myDatabase.execSQL("CREATE TABLE exams (id INTEGER PRIMARY KEY, exam VARCHAR, course VARCHAR, title VARCHAR, slot VARCHAR, date VARCHAR, reporting VARCHAR, start_time VARCHAR, end_time VARCHAR, venue VARCHAR, location VARCHAR, seat VARCHAR)");

                        SimpleDateFormat hour24 = new SimpleDateFormat("HH:mm", Locale.ENGLISH);
                        SimpleDateFormat hour12 = new SimpleDateFormat("h:mm a", Locale.ENGLISH);

                        Iterator<?> keys = myObj.keys();

                        while (keys.hasNext()) {
                            String exam = (String) keys.next();
                            JSONObject schedule = new JSONObject(myObj.getString(exam));

                            for (int i = 0; i < schedule.length() / 9; ++i) {
                                String course = schedule.getString("course" + i);
                                String title = schedule.getString("title" + i);
                                String slot = schedule.getString("slot" + i);
                                String date = schedule.getString("date" + i);
                                String reporting = schedule.getString("reporting" + i);
                                String startTime = schedule.getString("start" + i);
                                String endTime = schedule.getString("end" + i);
                                String venue = schedule.getString("venue" + i);
                                String location = schedule.getString("location" + i);
                                String seat = schedule.getString("seat" + i);

                                venue = venue.replace("-", " - ").trim();
                                slot = slot.replace("+", " + ");

                                /*
                                    Converting to 24 hour format if necessary
                                 */

                                // Reporting Time
                                try {
                                    Date reportingTime = hour12.parse(reporting);

                                    if (reportingTime != null) {
                                        reporting = hour24.format(reportingTime);
                                    }
                                } catch (Exception ignored) {
                                }

                                // Start Time
                                try {
                                    Date startTimeDate = hour12.parse(startTime);

                                    if (startTimeDate != null) {
                                        startTime = hour24.format(startTimeDate);
                                    }
                                } catch (Exception ignored) {
                                }

                                // End Time
                                try {
                                    Date endTimeDate = hour12.parse(endTime);

                                    if (endTimeDate != null) {
                                        endTime = hour24.format(endTimeDate);
                                    }
                                } catch (Exception ignored) {
                                }

                                myDatabase.execSQL("INSERT INTO exams (exam, course, title, slot, date, reporting, start_time, end_time, venue, location, seat) VALUES ('" + exam.toUpperCase() + "', '" + course + "', '" + title + "', '" + slot + "', '" + date + "', '" + reporting + "', '" + startTime + "', '" + endTime + "', '" + venue + "', '" + location + "', '" + seat + "')");
                            }
                        }

                        int objLength = myObj.length();
                        if (objLength != sharedPreferences.getInt("examsCount", 0)) {
                            sharedPreferences.edit().putBoolean("newExams", true).apply();
                            sharedPreferences.edit().putInt("examsCount", objLength).apply();
                        }

                        ((Activity) context).runOnUiThread(() -> {
                            updateProgress();
                            downloadMarks();
                        });
                    } catch (Exception e) {
                        if (ErrorHandler.isPreRelease) {
                            errorHandler.appendStackTrace(e);
                        }

                        error(902);
                    }
                }).start();
            }
        });
    }

    /*
        Function to download marks
     */
    public void downloadMarks() {
        if (terminateDownload) {
            return;
        }

        setupProgress();    // Inflating the progress layout if it hasn't been inflated yet

        downloading.setText(R.string.downloading_marks);
        if (!layoutQueue.contains(progressLayout)) {
            layoutQueue.add(progressLayout);

            if (layoutQueue.size() == 1) {
                expand();
            }
        }

        webView.evaluateJavascript("(function() {" +
                "var data = 'semesterSubId=' + '" + semesterID + "' + '&authorizedID=' + $('#authorizedIDX').val();" +
                "var obj;" +
                "$.ajax({" +
                "    type: 'POST'," +
                "    url : 'examinations/doStudentMarkView'," +
                "    data : data," +
                "    async: false," +
                "    success: function(response) {" +
                "        obj = {};" +
                "        if(response.toLowerCase().includes('no data found')) {" +
                "            obj = false;" +
                "        } else {" +
                "            var doc = new DOMParser().parseFromString(response, 'text/html');" +
                "            var rows = doc.getElementById('fixedTableContainer').getElementsByTagName('tr');" +
                "            var heads = rows[0].getElementsByTagName('td');" +
                "            var columns = heads.length;" +
                "            var courseIndex, typeIndex, titleIndex, maxIndex, percentIndex, statusIndex, scoredIndex, weightageIndex, averageIndex, postedIndex;" +
                "            var course = '', type = '', flag = 0, k = 0;" +
                "            for (var i = 0; i < columns; ++i) {" +
                "                var heading = heads[i].innerText.toLowerCase();" +
                "                if (heading.includes('code')) {" +
                "                    courseIndex = i;" +
                "                    ++flag;" +
                "                }" +
                "                if (heading.includes('type')) {" +
                "                    typeIndex = i;" +
                "                    ++flag;" +
                "                }" +
                "                if (flag >= 2) {" +
                "                    break;" +
                "                }" +
                "            }" +
                "            flag = 0;" +
                "            for (var i = 1; i < rows.length; ++i) {" +
                "                if (rows[i].getElementsByTagName('table').length) {" +
                "                    var records = rows[i].getElementsByTagName('tr').length - 1;" +
                "                    var heads = rows[++i].getElementsByTagName('td');" +
                "                    if (!flag) {" +
                "                        for (var j = 0; j < heads.length; ++j) {" +
                "                            var heading = heads[j].innerText.toLowerCase();" +
                "                            if (heading.includes('title')) {" +
                "                                titleIndex = j;" +
                "                            }" +
                "                            if (heading.includes('max')) {" +
                "                                maxIndex = j;" +
                "                            }" +
                "                            if (heading.includes('%')) {" +
                "                                percentIndex = j;" +
                "                            }" +
                "                            if (heading.includes('status')) {" +
                "                                statusIndex = j;" +
                "                            }" +
                "                            if (heading.includes('scored')) {" +
                "                                scoredIndex = j;" +
                "                            }" +
                "                            if (heading.includes('weightage') && heading.includes('mark')) {" +
                "                                weightageIndex = j;" +
                "                            }" +
                "                            if (heading.includes('average')) {" +
                "                                averageIndex = j;" +
                "                            }" +
                "                            if (heading.includes('posted')) {" +
                "                                postedIndex = j;" +
                "                            }" +
                "                        }" +
                "                        ++flag;" +
                "                    }" +
                "                    for (var j = 0; j < records; ++j) {" +
                "                        var values = rows[++i].getElementsByTagName('td');" +
                "                        var temp = {};" +
                "                        temp['title'] = values[titleIndex].innerText.trim();" +
                "                        temp['max'] = values[maxIndex].innerText.trim();" +
                "                        temp['percent'] = values[percentIndex].innerText.trim();" +
                "                        temp['status'] = values[statusIndex].innerText.trim();" +
                "                        temp['scored'] = values[scoredIndex].innerText.trim();" +
                "                        temp['weightage'] = values[weightageIndex].innerText.trim();" +
                "                        temp['average'] = values[averageIndex].innerText.trim();" +
                "                        temp['posted'] = values[postedIndex].innerText.trim();" +
                "                        temp['course'] = course;" +
                "                        temp['type'] = type;" +
                "                        obj[k++] = temp;" +
                "                    }" +
                "                } else {" +
                "                    course = rows[i].getElementsByTagName('td')[courseIndex].innerText.trim();" +
                "                    type = rows[i].getElementsByTagName('td')[typeIndex].innerText.trim();" +
                "                }" +
                "            }" +
                "        }" +
                "    }" +
                "});" +
                "return obj;" +
                "})();", obj -> {
            /*
                obj is in the form of a JSON string like {"0": {"course": "MAT1001", "score": "48",...},...}
             */
            if (obj.equals("null")) {
                error(1001);
            } else if (obj.equals("false")) {
                new Thread(() -> {
                    myDatabase.execSQL("DROP TABLE IF EXISTS marks");
                    myDatabase.execSQL("CREATE TABLE marks (id INTEGER PRIMARY KEY, course VARCHAR, type VARCHAR, title VARCHAR, score VARCHAR, status VARCHAR, weightage VARCHAR, average VARCHAR, posted VARCHAR)");

                    ((Activity) context).runOnUiThread(() -> {
                        updateProgress();
                        downloadGrades();
                    });

                    sharedPreferences.edit().remove("newMarks").apply();
                }).start();
            } else {
                new Thread(() -> {
                    try {
                        JSONObject myObj = new JSONObject(obj);

                        myDatabase.execSQL("CREATE TABLE IF NOT EXISTS marks (id INTEGER PRIMARY KEY, course VARCHAR, type VARCHAR, title VARCHAR, score VARCHAR, status VARCHAR, weightage VARCHAR, average VARCHAR, posted VARCHAR)");
                        myDatabase.execSQL("DROP TABLE IF EXISTS marks_new");
                        myDatabase.execSQL("CREATE TABLE marks_new (id INTEGER PRIMARY KEY, course VARCHAR, type VARCHAR, title VARCHAR, score VARCHAR, status VARCHAR, weightage VARCHAR, average VARCHAR, posted VARCHAR)");

                        for (int i = 0; i < myObj.length(); ++i) {
                            JSONObject tempObj = new JSONObject(myObj.getString(Integer.toString(i)));
                            String course = tempObj.getString("course");
                            String type = tempObj.getString("type");
                            String title = tempObj.getString("title").toUpperCase();
                            String score = tempObj.getString("scored") + " / " + tempObj.getString("max");
                            String status = tempObj.getString("status");
                            String weightage = tempObj.getString("weightage") + " / " + tempObj.getString("percent");
                            String average = tempObj.getString("average");
                            String posted = tempObj.getString("posted");

                            myDatabase.execSQL("INSERT INTO marks_new (course, type, title, score, status, weightage, average, posted) VALUES('" + course + "', '" + type + "', '" + title + "', '" + score + "', '" + status + "', '" + weightage + "', '" + average + "', '" + posted + "')");
                        }

                        /*
                            Removing any marks if they were deleted for some reason
                         */
                        Cursor delete = myDatabase.rawQuery("SELECT id FROM marks WHERE course || title || type NOT IN (SELECT course || title || type FROM marks_new)", null);

                        int deleteIndex = delete.getColumnIndex("id");
                        delete.moveToFirst();

                        String newMarksString = sharedPreferences.getString("newMarks", "{}");
                        JSONObject newMarks = new JSONObject(newMarksString);

                        if (!newMarksString.equals("{}")) {
                            for (int i = 0; i < delete.getCount(); ++i, delete.moveToNext()) {
                                String id = delete.getString(deleteIndex);

                                if (newMarks.has(id)) {
                                    newMarks.remove(id);
                                }
                            }
                        }

                        delete.close();

                        /*
                            Updating IDs if they have changed
                         */
                        JSONObject tempMarks = new JSONObject(newMarks.toString());
                        Iterator<?> keys = tempMarks.keys();

                        while (keys.hasNext()) {
                            String oldID = (String) keys.next();
                            Cursor update = myDatabase.rawQuery("SELECT id FROM marks_new WHERE course || title || type = (SELECT course || title || type FROM marks WHERE id = " + oldID + ")", null);
                            update.moveToFirst();
                            String newID = update.getString(update.getColumnIndex("id"));

                            if (!oldID.equals(newID)) {
                                newMarks.remove(oldID);
                                newMarks.put(newID, true);
                            }

                            update.close();
                        }

                        /*
                            Adding the newly downloaded marks
                         */
                        Cursor add = myDatabase.rawQuery("SELECT id FROM marks_new WHERE course || title || type NOT IN (SELECT course || title || type FROM marks)", null);

                        int addIndex = add.getColumnIndex("id");
                        add.moveToFirst();

                        for (int i = 0; i < add.getCount(); ++i, add.moveToNext()) {
                            String id = add.getString(addIndex);
                            newMarks.put(id, true);
                        }

                        add.close();

                        myDatabase.execSQL("DROP TABLE IF EXISTS marks");
                        myDatabase.execSQL("ALTER TABLE marks_new RENAME TO marks");

                        sharedPreferences.edit().putString("newMarks", newMarks.toString()).apply();

                        ((Activity) context).runOnUiThread(() -> {
                            updateProgress();
                            downloadGrades();
                        });
                    } catch (Exception e) {
                        if (ErrorHandler.isPreRelease) {
                            errorHandler.appendStackTrace(e);
                        }

                        error(1002);
                    }
                }).start();
            }
        });
    }

    /*
        Function to download grades
     */
    public void downloadGrades() {
        if (terminateDownload) {
            return;
        }

        setupProgress();    // Inflating the progress layout if it hasn't been inflated yet

        downloading.setText(context.getString(R.string.downloading_grades));
        if (!layoutQueue.contains(progressLayout)) {
            layoutQueue.add(progressLayout);

            if (layoutQueue.size() == 1) {
                expand();
            }
        }
        webView.evaluateJavascript("(function() {" +
                "var data = 'semesterSubId=' + '" + semesterID + "' + '&authorizedID=' + $('#authorizedIDX').val();" +
                "var obj;" +
                "$.ajax({" +
                "    type: 'POST'," +
                "    url : 'examinations/examGradeView/doStudentGradeView'," +
                "    data : data," +
                "    async: false," +
                "    success: function(response) {" +
                "        obj = {};" +
                "        if(response.toLowerCase().includes('no records')) {" +
                "            obj = false;" +
                "        } else {" +
                "            var doc = new DOMParser().parseFromString(response, 'text/html');" +
                "            var courseIndex, typeIndex, gradeTypeIndex, totalIndex, gradeIndex;" +
                "            var creditsIndex, creditsSpan, flag = 0;" +
                "            var columns = doc.getElementsByTagName('tr')[0].getElementsByTagName('th');" +
                "            for (var i = 0; i < columns.length; ++i) {" +
                "                var heading = columns[i].innerText.toLowerCase();" +
                "                if (heading.includes('code')) {" +
                "                    courseIndex = i;" +
                "                    ++flag;" +
                "                } else if (heading.includes('course') && heading.includes('type')) {" +
                "                    typeIndex = i;" +
                "                    ++flag;" +
                "                } else if (heading.includes('grading')) {" +
                "                    gradeTypeIndex = i;" +
                "                    ++flag;" +
                "                } else if (heading.includes('total')) {" +
                "                    totalIndex = i;" +
                "                    ++flag;" +
                "                } else if (heading.includes('grade')) {" +
                "                    gradeIndex = i;" +
                "                    ++flag;" +
                "                } else if (heading.includes('credits')) {" +
                "                    creditsIndex = i;" +
                "                    creditsSpan = columns[i].colSpan;" +
                "                    ++flag;" +
                "                }" +
                "                if (flag >= 6) {" +
                "                    break;" +
                "                }" +
                "            }" +
                "            if (courseIndex > creditsIndex) {" +
                "                courseIndex += creditsSpan - 1;" +
                "            }" +
                "            if (typeIndex > creditsIndex) {" +
                "                typeIndex += creditsSpan - 1;" +
                "            }" +
                "            if (gradeTypeIndex > creditsIndex) {" +
                "                gradeTypeIndex += creditsSpan - 1;" +
                "            }" +
                "            if (totalIndex > creditsIndex) {" +
                "                totalIndex += creditsSpan - 1;" +
                "            }" +
                "            if (gradeIndex > creditsIndex) {" +
                "                gradeIndex += creditsSpan - 1;" +
                "            }" +
                "            var cells = doc.getElementsByTagName('td');" +
                "            var columnLength = columns.length + creditsSpan - 1;" +
                "            for (var i = 0; courseIndex < cells.length && typeIndex < cells.length && gradeTypeIndex < cells.length && totalIndex < cells.length && gradeIndex < cells.length; ++i) {" +
                "                var temp = {};" +
                "                temp['course'] = cells[courseIndex].innerText.trim();" +
                "                temp['type'] = cells[typeIndex].innerText.trim();" +
                "                temp['gradetype'] = cells[gradeTypeIndex].innerText.trim();" +
                "                temp['total'] = cells[totalIndex].innerText.trim();" +
                "                temp['grade'] = cells[gradeIndex].innerText.trim();" +
                "                obj[i] = temp;" +
                "                courseIndex += columnLength;" +
                "                typeIndex += columnLength;" +
                "                gradeTypeIndex += columnLength;" +
                "                totalIndex += columnLength;" +
                "                gradeIndex += columnLength;" +
                "            }" +
                "            obj['gpa'] = cells[cells.length - 1].innerText.split(':')[1].trim();" +
                "        }" +
                "    }" +
                "});" +
                "return obj;" +
                "})();", obj -> {
            /*
                obj is in the form of a JSON string like {"0": {"course": "MAT1001", "type": "Embedded Theory",...},..., "gpa": "9.6"}
             */
            if (obj.equals("null")) {
                error(1101);
            } else if (obj.equals("false")) {
                /*
                    Dropping and recreating an empty table
                 */
                new Thread(() -> {
                    myDatabase.execSQL("DROP TABLE IF EXISTS grades");
                    myDatabase.execSQL("CREATE TABLE grades (id INTEGER PRIMARY KEY, course VARCHAR, type VARCHAR, grade_type VARCHAR, total VARCHAR, grade VARCHAR)");

                    ((Activity) context).runOnUiThread(() -> {
                        updateProgress();
                        downloadGradeHistory();
                    });

                    sharedPreferences.edit().remove("newGrades").apply();
                    sharedPreferences.edit().remove("gradesCount").apply();
                    sharedPreferences.edit().remove("gpa").apply();
                }).start();
            } else {
                /*
                    Dropping, recreating and adding grades
                 */
                new Thread(() -> {
                    try {
                        myDatabase.execSQL("DROP TABLE IF EXISTS grades");
                        myDatabase.execSQL("CREATE TABLE grades (id INTEGER PRIMARY KEY, course VARCHAR, type VARCHAR, grade_type VARCHAR, total VARCHAR, grade VARCHAR)");

                        JSONObject myObj = new JSONObject(obj);

                        int i;
                        for (i = 0; i < myObj.length() - 1; ++i) {
                            JSONObject tempObj = new JSONObject(myObj.getString(String.valueOf(i)));
                            String course = tempObj.getString("course");
                            String type = tempObj.getString("type");
                            String gradeType = tempObj.getString("gradetype");
                            String total = tempObj.getString("total") + " / 100";
                            String grade = tempObj.getString("grade");

                            if (gradeType.equalsIgnoreCase("ag")) {
                                gradeType = "Absolute";
                            } else if (gradeType.equalsIgnoreCase("rg")) {
                                gradeType = "Relative";
                            }

                            myDatabase.execSQL("INSERT INTO grades (course, type, grade_type, total, grade) VALUES('" + course + "', '" + type + "', '" + gradeType + "', '" + total + "', '" + grade + "')");
                        }

                        if (i != sharedPreferences.getInt("gradesCount", 0)) {
                            sharedPreferences.edit().putBoolean("newGrades", true).apply();
                            sharedPreferences.edit().putInt("gradesCount", i).apply();
                        }

                        String gpa = myObj.getString("gpa");
                        sharedPreferences.edit().putString("gpa", gpa).apply();

                        ((Activity) context).runOnUiThread(() -> {
                            updateProgress();
                            downloadGradeHistory();
                        });
                    } catch (Exception e) {
                        if (ErrorHandler.isPreRelease) {
                            errorHandler.appendStackTrace(e);
                        }

                        error(1102);
                    }
                }).start();
            }
        });
    }

    /*
        Function to download grade history
     */
    public void downloadGradeHistory() {
        if (terminateDownload) {
            return;
        }

        setupProgress();    // Inflating the progress layout if it hasn't been inflated yet

        downloading.setText(context.getString(R.string.downloading_grade_history));
        if (!layoutQueue.contains(progressLayout)) {
            layoutQueue.add(progressLayout);

            if (layoutQueue.size() == 1) {
                expand();
            }
        }

        webView.evaluateJavascript("(function() {" +
                "var data = 'verifyMenu=true&winImage=' + $('#winImage').val() + '&authorizedID=' + $('#authorizedIDX').val() + '&nocache=@(new Date().getTime())';" +
                "var obj;" +
                "$.ajax({" +
                "    type: 'POST'," +
                "    url : 'examinations/examGradeView/StudentGradeHistory'," +
                "    data : data," +
                "    async: false," +
                "    success: function(response) {" +
                "        var doc = new DOMParser().parseFromString(response, 'text/html');" +
                "        var tables = doc.getElementsByTagName('table');" +
                "        obj = {};" +
                "        for (var i = 0; i < tables.length; ++i) {" +
                "            var category = tables[i].getElementsByTagName('td')[0].innerText.trim().toLowerCase();" +
                "            if (category.includes('reg') && !category.includes('credits')) {" +
                "                continue;" +
                "            } else if (category.includes('effective')) {" +
                "                category = 'effective';" +
                "                var courseIndex, titleIndex, creditsIndex, gradeIndex, flag = 0;" +
                "                var columns = tables[i].getElementsByTagName('tr')[1].getElementsByTagName('td');" +
                "                for (var j = 0; j < columns.length; ++j) {" +
                "                    var heading = columns[j].innerText.trim().toLowerCase();" +
                "                    if (heading.includes('code')) {" +
                "                        courseIndex = j + columns.length + 1;" +
                "                        ++flag;" +
                "                    } else if (heading.includes('title')) {" +
                "                        titleIndex = j + columns.length + 1;" +
                "                        ++flag;" +
                "                    } else if (heading.includes('credits')) {" +
                "                        creditsIndex = j + columns.length + 1;" +
                "                        ++flag;" +
                "                    } else if (heading.includes('grade')) {" +
                "                        gradeIndex = j + columns.length + 1;" +
                "                        ++flag;" +
                "                    }" +
                "                    if (flag >= 4) {" +
                "                        break;" +
                "                    }" +
                "                }" +
                "                var temp = {};" +
                "                var cells = tables[i].getElementsByTagName('td');" +
                "                for (var j = 0; j < cells.length; ++j) {" +
                "                    if (cells[j].getElementsByTagName('table').length != 0) {" +
                "                        cells[j].remove();" +
                "                    }" +
                "                }" +
                "                for (var j = 0; courseIndex < cells.length && titleIndex < cells.length && creditsIndex < cells.length && gradeIndex < cells.length; ++j) {" +
                "                    temp['course' + j] = cells[courseIndex].innerText.trim();" +
                "                    temp['title' + j] = cells[titleIndex].innerText.trim();" +
                "                    temp['credits' + j] = cells[creditsIndex].innerText.trim();" +
                "                    temp['grade' + j] = cells[gradeIndex].innerText.trim();" +
                "                    courseIndex += columns.length;" +
                "                    titleIndex += columns.length;" +
                "                    creditsIndex += columns.length;" +
                "                    gradeIndex += columns.length;" +
                "                }" +
                "                obj[category] = temp;" +
                "            } else if (category.includes('curriculum')) {" +
                "                category = 'curriculum';" +
                "                var typeIndex, requiredIndex, earnedIndex;" +
                "                var columns = tables[i].getElementsByTagName('tr')[1].getElementsByTagName('td');" +
                "                for (var j = 0; j < columns.length; ++j) {" +
                "                    var heading = columns[j].innerText.trim().toLowerCase();" +
                "                    if (heading.includes('type')) {" +
                "                        typeIndex = j + columns.length + 1;" +
                "                    } else if (heading.includes('required')) {" +
                "                        requiredIndex = j + columns.length + 1;" +
                "                    } else if (heading.includes('earned')) {" +
                "                        earnedIndex = j + columns.length + 1;" +
                "                    }" +
                "                }" +
                "                var temp = {};" +
                "                var cells = tables[i].getElementsByTagName('td');" +
                "                for (var j = 0; typeIndex < cells.length && requiredIndex < cells.length && earnedIndex < cells.length; ++j) {" +
                "                    temp['type' + j] = cells[typeIndex].innerText.trim();" +
                "                    temp['required' + j] = cells[requiredIndex].innerText.trim();" +
                "                    temp['earned' + j] = cells[earnedIndex].innerText.trim();" +
                "                    typeIndex += columns.length;" +
                "                    requiredIndex += columns.length;" +
                "                    earnedIndex += columns.length;" +
                "                }" +
                "                obj[category] = temp;" +
                "            } else if (category.includes('basket')) {" +
                "                category = 'basket';" +
                "                var titleIndex, requiredIndex, earnedIndex;" +
                "                var columns = tables[i].getElementsByTagName('tr')[1].getElementsByTagName('td');" +
                "                for (var j = 0; j < columns.length; ++j) {" +
                "                    var heading = columns[j].innerText.trim().toLowerCase();" +
                "                    if (heading.includes('title')) {" +
                "                        titleIndex = j + columns.length + 1;" +
                "                    } else if (heading.includes('required')) {" +
                "                        requiredIndex = j + columns.length + 1;" +
                "                    } else if (heading.includes('earned')) {" +
                "                        earnedIndex = j + columns.length + 1;" +
                "                    }" +
                "                }" +
                "                var temp = {};" +
                "                var cells = tables[i].getElementsByTagName('td');" +
                "                for (var j = 0; titleIndex < cells.length && requiredIndex < cells.length && earnedIndex < cells.length; ++j) {" +
                "                    temp['title' + j] = cells[titleIndex].innerText.trim();" +
                "                    temp['required' + j] = cells[requiredIndex].innerText.trim();" +
                "                    temp['earned' + j] = cells[earnedIndex].innerText.trim();" +
                "                    titleIndex += columns.length;" +
                "                    requiredIndex += columns.length;" +
                "                    earnedIndex += columns.length;" +
                "                }" +
                "                obj[category] = temp;" +
                "            } else {" +
                "                category = 'summary';" +
                "                var columns = tables[i].getElementsByTagName('tr')[0].getElementsByTagName('td');" +
                "                var cells = tables[i].getElementsByTagName('td');" +
                "                var temp = {};" +
                "                for (var j = 0; j < columns.length; ++j) {" +
                "                    var heading = columns[j].innerText.trim();" +
                "                    var prefix = j;" +
                "                    if (j < 10) {" +
                "                        prefix = '0' + j;" +
                "                    }" +
                "                    temp[prefix + heading] = cells[j + columns.length].innerText.trim();" +
                "                }" +
                "                obj[category] = temp;" +
                "            }" +
                "        }" +
                "    }" +
                "});" +
                "return obj;" +
                "})();", obj -> {
            /*
                obj is in the form of a JSON string like {"effective": "{course: "MAT1011", ...}", "curriculum": "{"type": "Program Core", ...}, ...}
             */
            if (obj.equals("null")) {
                error(1103);
            } else {
                new Thread(() -> {
                    try {
                        JSONObject myObj = new JSONObject(obj);

                        myDatabase.execSQL("DROP TABLE IF EXISTS grades_effective");
                        myDatabase.execSQL("CREATE TABLE grades_effective (id INTEGER PRIMARY KEY, course VARCHAR, title VARCHAR, credits VARCHAR, grade VARCHAR)");

                        /*
                            Storing the effective grades
                         */
                        JSONObject effective = new JSONObject(myObj.getString("effective"));

                        for (int i = 0; i < effective.length() / 4; ++i) {
                            String course = effective.getString("course" + i);
                            String title = effective.getString("title" + i);
                            String credits = effective.getString("credits" + i);
                            String grade = effective.getString("grade" + i);

                            myDatabase.execSQL("INSERT INTO grades_effective (course, title, credits, grade) VALUES('" + course + "', '" + title + "', '" + credits + "', '" + grade + "')");
                        }

                        myDatabase.execSQL("DROP TABLE IF EXISTS grades_curriculum");
                        myDatabase.execSQL("CREATE TABLE grades_curriculum (id INTEGER PRIMARY KEY, type VARCHAR, credits VARCHAR)");

                        /*
                            Storing the curriculum details
                         */
                        JSONObject curriculum = new JSONObject(myObj.getString("curriculum"));

                        for (int i = 0; i < curriculum.length() / 3; ++i) {
                            String type = curriculum.getString("type" + i);
                            String required = curriculum.getString("required" + i);
                            String earned = curriculum.getString("earned" + i);

                            if (required.equals("-")) {
                                required = "0";
                            }

                            if (earned.equals("")) {
                                earned = "0";
                            }

                            String credits = earned + " / " + required;

                            myDatabase.execSQL("INSERT INTO grades_curriculum (type, credits) VALUES('" + type + "', '" + credits + "')");
                        }

                        myDatabase.execSQL("DROP TABLE IF EXISTS grades_basket");
                        myDatabase.execSQL("CREATE TABLE grades_basket (id INTEGER PRIMARY KEY, title VARCHAR, credits VARCHAR)");

                        /*
                            Storing the basket grades
                         */
                        JSONObject basket = new JSONObject(myObj.getString("basket"));

                        for (int i = 0; i < basket.length() / 3; ++i) {
                            String title = basket.getString("title" + i);
                            String credits = basket.getString("earned" + i) + " / " + basket.getString("required" + i);

                            myDatabase.execSQL("INSERT INTO grades_basket (title, credits) VALUES('" + title + "', '" + credits + "')");
                        }

                        myDatabase.execSQL("DROP TABLE IF EXISTS grades_summary");
                        myDatabase.execSQL("CREATE TABLE grades_summary (id INTEGER PRIMARY KEY, column1 VARCHAR, column2 VARCHAR)");

                        /*
                            Storing the summary
                         */
                        JSONObject summary = new JSONObject(myObj.getString("summary"));

                        Iterator<?> keys = summary.keys();

                        while (keys.hasNext()) {
                            String key = (String) keys.next();
                            String value = summary.getString(key);

                            key = key.substring(2);

                            if (key.toLowerCase().contains("cgpa")) {
                                key = "Overall CGPA";
                            } else if (key.toLowerCase().contains("grades")) {
                                key = "Number of " + key;
                            }

                            myDatabase.execSQL("INSERT INTO grades_summary (column1, column2) VALUES('" + key + "', '" + value + "')");
                        }

                        ((Activity) context).runOnUiThread(() -> {
                            updateProgress();
                            downloadMessages();
                        });
                    } catch (Exception e) {
                        if (ErrorHandler.isPreRelease) {
                            errorHandler.appendStackTrace(e);
                        }

                        error(1104);
                    }
                }).start();
            }
        });
    }

    /*
        Function to store the class messages (1 / 2 - Messages) in the SQLite database.
     */
    public void downloadMessages() {
        if (terminateDownload) {
            return;
        }

        setupProgress();    // Inflating the progress layout if it hasn't been inflated yet

        downloading.setText(context.getString(R.string.downloading_messages));
        if (!layoutQueue.contains(progressLayout)) {
            layoutQueue.add(progressLayout);

            if (layoutQueue.size() == 1) {
                expand();
            }
        }

        webView.evaluateJavascript("(function() {" +
                "var data = 'verifyMenu=true&winImage=' + $('#winImage').val() + '&authorizedID=' + $('#authorizedIDX').val() + '&nocache=@(new Date().getTime())';" +
                "var obj;" +
                "$.ajax({" +
                "    type: 'POST'," +
                "    url : 'academics/common/StudentClassMessage'," +
                "    data : data," +
                "    async: false," +
                "    success: function(response) {" +
                "        obj = {};" +
                "        if(response.toLowerCase().includes('no messages')) {" +
                "            obj = false;" +
                "        } else {" +
                "            var doc = new DOMParser().parseFromString(response, 'text/html');" +
                "            var messages = doc.getElementsByTagName('a');" +
                "            for (var i = 0; i < messages.length; ++i) {" +
                "                var temp = {};" +
                "                var content = messages[i].getElementsByTagName('span');" +
                "                var course = content[0].innerText.split('-');" +
                "                temp['message'] = content[1].innerText;" +
                "                temp['course'] = course[0].trim();" +
                "                temp['type'] = course[course.length - 1].trim();" +
                "                obj[i] = temp;" +
                "            }" +
                "        }" +
                "    }" +
                "});" +
                "return obj;" +
                "})();", obj -> {
            /*
                obj is in the form of a JSON string like {"0": {"course": "MAT1001", "type": "Embedded Theory", "message": "All of you have failed!"}}
             */
            if (obj.equals("null")) {
                error(1201);
            } else if (obj.equals("false")) {
                /*
                    Dropping and recreating an empty table
                 */
                new Thread(() -> {
                    myDatabase.execSQL("DROP TABLE IF EXISTS messages");
                    myDatabase.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY, course VARCHAR, type VARCHAR, message VARCHAR)");

                    ((Activity) context).runOnUiThread(() -> {
                        updateProgress();
                        downloadProctorMessages();
                    });

                    sharedPreferences.edit().remove("newMessages").apply();
                }).start();
            } else {
                /*
                    Dropping, recreating and adding messages
                 */
                new Thread(() -> {
                    try {
                        myDatabase.execSQL("CREATE TABLE IF NOT EXISTS messages (id INTEGER PRIMARY KEY, course VARCHAR, type VARCHAR, message VARCHAR)");
                        myDatabase.execSQL("DROP TABLE IF EXISTS messages_new");
                        myDatabase.execSQL("CREATE TABLE messages_new (id INTEGER PRIMARY KEY, course VARCHAR, type VARCHAR, message VARCHAR)");

                        JSONObject myObj = new JSONObject(obj);

                        for (int i = 0; i < myObj.length(); ++i) {
                            JSONObject tempObj = new JSONObject(myObj.getString(Integer.toString(i)));
                            String course = tempObj.getString("course");
                            String type = tempObj.getString("type");
                            String message = tempObj.getString("message");

                            myDatabase.execSQL("INSERT INTO messages_new (course, type, message) VALUES('" + course + "', '" + type + "', '" + message + "')");
                        }

                        /*
                            Checking for messages that haven't been downloaded before
                         */
                        Cursor newMessages = myDatabase.rawQuery("SELECT id FROM messages_new WHERE message NOT IN (SELECT message FROM messages)", null);

                        if (newMessages.getCount() > 0) {
                            sharedPreferences.edit().putBoolean("newMessages", true).apply();
                        }

                        newMessages.close();

                        myDatabase.execSQL("DROP TABLE messages");
                        myDatabase.execSQL("ALTER TABLE messages_new RENAME TO messages");

                        ((Activity) context).runOnUiThread(() -> {
                            updateProgress();
                            downloadProctorMessages();
                        });
                    } catch (Exception e) {
                        if (ErrorHandler.isPreRelease) {
                            errorHandler.appendStackTrace(e);
                        }

                        error(1202);
                    }
                }).start();
            }
        });
    }

    /*
        Function to store the proctor messages (2 / 2 - Messages) in the SQLite database.
     */
    public void downloadProctorMessages() {
        if (terminateDownload) {
            return;
        }

        setupProgress();    // Inflating the progress layout if it hasn't been inflated yet

        downloading.setText(context.getString(R.string.downloading_messages));
        if (!layoutQueue.contains(progressLayout)) {
            layoutQueue.add(progressLayout);

            if (layoutQueue.size() == 1) {
                expand();
            }
        }
        webView.evaluateJavascript("(function() {" +
                "var data = 'verifyMenu=true&winImage=' + $('#winImage').val() + '&authorizedID=' + $('#authorizedIDX').val() + '&nocache=@(new Date().getTime())';" +
                "var successFlag;" +
                "$.ajax({" +
                "    type: 'POST'," +
                "    url : 'proctor/viewMessagesSendByProctor'," +
                "    data : data," +
                "    async: false," +
                "    success: function(response) {" +
                "        if(response.toLowerCase().includes('no messages')) {" +
                "            successFlag = true;" +
                "        } else {" +
                "            successFlag = false;" +
                "        }" +
                "    }" +
                "});" +
                "return successFlag;" +
                "})();", value -> {
            /*
                obj is in the form of a JSON string that is yet to be created
             */
            String temp = value.substring(1, value.length() - 1);
            if (value.equals("true")) {
                /*
                    Dropping and recreating an empty table
                 */
                new Thread(() -> {
                    myDatabase.execSQL("DROP TABLE IF EXISTS proctor_messages");
                    myDatabase.execSQL("CREATE TABLE proctor_messages (id INTEGER PRIMARY KEY, time VARCHAR, message VARCHAR)");

                    ((Activity) context).runOnUiThread(() -> {
                        updateProgress();
                        downloadSpotlight();
                    });

                    sharedPreferences.edit().remove("newProctorMessages").apply();
                }).start();
            } else if (temp.equals("false")) {
                /*
                    Dropping, recreating and adding new proctor messages
                 */
                new Thread(() -> {
                    try {
                        myDatabase.execSQL("DROP TABLE IF EXISTS proctor_messages");
                        myDatabase.execSQL("CREATE TABLE proctor_messages (id INTEGER PRIMARY KEY, time VARCHAR, message VARCHAR)");

                        myDatabase.execSQL("INSERT INTO proctor_messages (time, message) VALUES('null', 'null')"); //To be changed with the actual announcements

                        ((Activity) context).runOnUiThread(() -> {
                            updateProgress();
                            downloadSpotlight();
                        });

                        sharedPreferences.edit().putBoolean("newProctorMessages", true).apply();
                    } catch (Exception e) {
                        if (ErrorHandler.isPreRelease) {
                            errorHandler.appendStackTrace(e);
                        }

                        error(1204);
                    }
                }).start();
            } else {
                error(1203);
            }
        });
    }

    /*
        Function to store spotlight in the SQLite database.
     */
    public void downloadSpotlight() {
        if (terminateDownload) {
            return;
        }

        setupProgress();    // Inflating the progress layout if it hasn't been inflated yet

        downloading.setText(context.getString(R.string.downloading_spotlight));
        if (!layoutQueue.contains(progressLayout)) {
            layoutQueue.add(progressLayout);

            if (layoutQueue.size() == 1) {
                expand();
            }
        }

        webView.evaluateJavascript("(function() {" +
                "var data = 'verifyMenu=true&winImage=' + $('#winImage').val() + '&authorizedID=' + $('#authorizedIDX').val() + '&nocache=@(new Date().getTime())';" +
                "var obj;" +
                "$.ajax({" +
                "    type: 'POST'," +
                "    url : 'spotlight/spotlightViewOld'," +
                "    data : data," +
                "    async: false," +
                "    success: function(response) {" +
                "        var doc = new DOMParser().parseFromString(response, 'text/html');" +
                "        obj = {};" +
                "        if(!doc.getElementsByClassName('box-info')) {" +
                "            obj = false;" +
                "        } else {" +
                "            var modals = doc.getElementsByClassName('modal-content');" +
                "            for(var i = 0; i < modals.length; ++i) {" +
                "                var category = modals[i].getElementsByTagName('h5')[0].innerText;" +
                "                if(category.toLowerCase().includes('finance')) {" +
                "                    category = 'Others';" +
                "                } else {" +
                "                    category = category.replace(/\\t/g,'').replace(/\\n/g,'').trim();" +
                "                    category = category.substring(0, category.length - 9).trim();" +
                "                }" +
                "                var announcements = modals[i].getElementsByTagName('li');" +
                "                if (announcements.length == 0) {" +
                "                    continue;" +
                "                }" +
                "                var temp = {};" +
                "                for(var j = 0; j < announcements.length; ++j) {" +
                "                    temp[j + 'announcement'] = announcements[j].innerText.replace(/\\t/g,'').replace(/\\n/g,' ').trim();" +
                "                    if(!announcements[j].getElementsByTagName('a').length) {" +
                "                        temp[j + 'link'] = 'null';" +
                "                    } else {" +
                "                        temp[j + 'link'] = announcements[j].getElementsByTagName('a')[0].href;" +
                "                        if(temp[j + 'link'].includes('\\'')) {" +
                "                            temp[j + 'link'] = announcements[j].getElementsByTagName('a')[0].href.split('\\'')[1];" +
                "                        }" +
                "                    }" +
                "                }" +
                "                obj[category] = temp;" +
                "            }" +
                "        }" +
                "    }" +
                "});" +
                "return obj;" +
                "})();", obj -> {
            /*
                obj is in the form of a JSON string like {"Academics": {"announcement": "In lieu of COVID-19, campus will remain shut for eternity.", "link": "null"},...}
             */
            if (obj.equals("null")) {
                error(1301);
            } else if (obj.equals("false")) {
                /*
                    Dropping and recreating an empty table
                 */
                new Thread(() -> {
                    myDatabase.execSQL("DROP TABLE IF EXISTS spotlight");
                    myDatabase.execSQL("CREATE TABLE spotlight (id INTEGER PRIMARY KEY, category VARCHAR, announcement VARCHAR, link VARCHAR)");

                    ((Activity) context).runOnUiThread(() -> {
                        updateProgress();
                        downloadReceipts();
                    });

                    sharedPreferences.edit().remove("newSpotlight").apply();
                }).start();
            } else {
                /*
                    Dropping, recreating and adding announcements
                 */
                new Thread(() -> {
                    try {
                        JSONObject myObj = new JSONObject(obj);

                        myDatabase.execSQL("CREATE TABLE IF NOT EXISTS spotlight (id INTEGER PRIMARY KEY, category VARCHAR, announcement VARCHAR, link VARCHAR)");
                        myDatabase.execSQL("DROP TABLE IF EXISTS spotlight_new");
                        myDatabase.execSQL("CREATE TABLE spotlight_new (id INTEGER PRIMARY KEY, category VARCHAR, announcement VARCHAR, link VARCHAR)");

                        Iterator<?> keys = myObj.keys();

                        while (keys.hasNext()) {
                            String category = (String) keys.next();
                            JSONObject tempObj = new JSONObject(myObj.getString(category));

                            for (int i = 0; i < tempObj.length() / 2; ++i) {
                                String announcement = tempObj.getString(i + "announcement");
                                String link = tempObj.getString(i + "link");

                                myDatabase.execSQL("INSERT INTO spotlight_new (category, announcement, link) VALUES('" + category + "', '" + announcement + "', '" + link + "')");
                            }
                        }

                        /*
                            Removing any announcements if they were deleted on the portal
                         */
                        Cursor delete = myDatabase.rawQuery("SELECT id FROM spotlight WHERE announcement NOT IN (SELECT announcement FROM spotlight_new)", null);

                        int deleteIndex = delete.getColumnIndex("id");
                        delete.moveToFirst();

                        String newSpotlightString = sharedPreferences.getString("newSpotlight", "{}");
                        JSONObject newSpotlight = new JSONObject(newSpotlightString);

                        if (!newSpotlightString.equals("{}")) {
                            for (int i = 0; i < delete.getCount(); ++i, delete.moveToNext()) {
                                String id = delete.getString(deleteIndex);

                                if (newSpotlight.has(id)) {
                                    newSpotlight.remove(id);
                                }
                            }
                        }

                        delete.close();

                        /*
                            Updating any announcements IDs that have been changed in the new table
                         */
                        JSONObject tempSpotlight = new JSONObject(newSpotlight.toString());
                        keys = tempSpotlight.keys();

                        while (keys.hasNext()) {
                            String oldID = (String) keys.next();
                            Cursor update = myDatabase.rawQuery("SELECT id FROM spotlight_new WHERE announcement IN (SELECT announcement FROM spotlight WHERE id = " + oldID + ")", null);
                            update.moveToFirst();
                            String newID = update.getString(update.getColumnIndex("id"));

                            if (!oldID.equals(newID)) {
                                newSpotlight.remove(oldID);
                                newSpotlight.put(newID, true);
                            }

                            update.close();
                        }

                        /*
                            Adding the new announcements
                         */
                        Cursor add = myDatabase.rawQuery("SELECT id FROM spotlight_new WHERE announcement NOT IN (SELECT announcement FROM spotlight)", null);

                        int addIndex = add.getColumnIndex("id");
                        add.moveToFirst();

                        for (int i = 0; i < add.getCount(); ++i, add.moveToNext()) {
                            String id = add.getString(addIndex);
                            newSpotlight.put(id, true);
                        }

                        add.close();

                        myDatabase.execSQL("DROP TABLE spotlight");
                        myDatabase.execSQL("ALTER TABLE spotlight_new RENAME TO spotlight");

                        sharedPreferences.edit().putString("newSpotlight", newSpotlight.toString()).apply();

                        ((Activity) context).runOnUiThread(() -> {
                            updateProgress();
                            downloadReceipts();
                        });
                    } catch (Exception e) {
                        if (ErrorHandler.isPreRelease) {
                            errorHandler.appendStackTrace(e);
                        }

                        error(1302);
                    }
                }).start();
            }
        });
    }

    /*
        Function to store payment receipts
    */
    public void downloadReceipts() {
        if (terminateDownload) {
            return;
        }

        setupProgress();    // Inflating the progress layout if it hasn't been inflated yet

        downloading.setText(context.getString(R.string.downloading_receipts));
        if (!layoutQueue.contains(progressLayout)) {
            layoutQueue.add(progressLayout);

            if (layoutQueue.size() == 1) {
                expand();
            }
        }

        webView.evaluateJavascript("(function() {" +
                "var data = 'verifyMenu=true&winImage=' + $('#winImage').val() + '&authorizedID=' + $('#authorizedIDX').val() + '&nocache=@(new Date().getTime())';" +
                "var obj;" +
                "$.ajax({" +
                "    type: 'POST'," +
                "    url : 'p2p/getReceiptsApplno'," +
                "    data : data," +
                "    async: false," +
                "    success: function(response) {" +
                "        var doc = new DOMParser().parseFromString(response, 'text/html');" +
                "        var receiptIndex, dateIndex, amountIndex, flag = 0;" +
                "        var columns = doc.getElementsByTagName('tr')[0].getElementsByTagName('td').length;" +
                "        var cells = doc.getElementsByTagName('td');" +
                "        obj = {};" +
                "        for(var i = 0; i < columns; ++i) {" +
                "            var heading = cells[i].innerText.toLowerCase();" +
                "            if(heading.includes('receipt')) {" +
                "                receiptIndex = i + columns;" +
                "                ++flag;" +
                "            }" +
                "            if(heading.includes('date')) {" +
                "                dateIndex = i + columns;" +
                "                ++flag;" +
                "            }" +
                "            if(heading.includes('amount')) {" +
                "                amountIndex = i + columns;" +
                "                ++flag;" +
                "            }" +
                "            if(flag >= 3) {" +
                "                break;" +
                "            }" +
                "        }" +
                "        for(var i = 0; receiptIndex < cells.length && dateIndex < cells.length && amountIndex < cells.length; ++i) {" +
                "            var temp = {};" +
                "            temp['receipt'] = cells[receiptIndex].innerText.trim();" +
                "            temp['date'] = cells[dateIndex].innerText.trim();" +
                "            temp['amount'] = cells[amountIndex].innerText.trim();" +
                "            obj[i] = temp;" +
                "            receiptIndex += columns;" +
                "            dateIndex += columns;" +
                "            amountIndex += columns;" +
                "        }" +
                "    }" +
                "});" +
                "return obj;" +
                "})();", obj -> {
            /*
                obj is in the form of a JSON string like {"0": {"amount": "1000000", "date": "04-JAN-1976", "receipt": "17085"},...}
             */
            if (obj.equals("null")) {
                error(1401);
            } else {
                new Thread(() -> {
                    try {
                        JSONObject myObj = new JSONObject(obj);

                        myDatabase.execSQL("DROP TABLE IF EXISTS receipts");
                        myDatabase.execSQL("CREATE TABLE receipts (id INTEGER PRIMARY KEY, receipt VARCHAR, date VARCHAR, amount VARCHAR)");

                        int i;
                        for (i = 0; i < myObj.length(); ++i) {
                            JSONObject tempObj = new JSONObject(myObj.getString(Integer.toString(i)));
                            String receipt = tempObj.getString("receipt");
                            String date = tempObj.getString("date").toUpperCase();
                            String amount = tempObj.getString("amount");

                            myDatabase.execSQL("INSERT INTO receipts (receipt, date, amount) VALUES('" + receipt + "', '" + date + "', '" + amount + "')");
                        }

                        if (i != sharedPreferences.getInt("receiptsCount", 0)) {
                            sharedPreferences.edit().putBoolean("newReceipts", true).apply();
                            sharedPreferences.edit().putInt("receiptsCount", i).apply();
                        }

                        ((Activity) context).runOnUiThread(() -> {
                            updateProgress();
                            checkDues();
                        });
                    } catch (Exception e) {
                        if (ErrorHandler.isPreRelease) {
                            errorHandler.appendStackTrace(e);
                        }

                        error(1402);
                    }
                }).start();
            }
        });
    }

    /*
        Check for payment dues
     */
    public void checkDues() {
        if (terminateDownload) {
            return;
        }

        webView.evaluateJavascript("(function() {" +
                "var data = 'verifyMenu=true&winImage=' + $('#winImage').val() + '&authorizedID=' + $('#authorizedIDX').val() + '&nocache=@(new Date().getTime())';" +
                "var duePayments;" +
                "$.ajax({" +
                "    type: 'POST'," +
                "    url : 'p2p/Payments'," +
                "    data : data," +
                "    async: false," +
                "    success: function(response) {" +
                "        if (response.toLowerCase().includes('pay now')) {" +
                "            duePayments = true;" +
                "        } else {" +
                "            duePayments = false;" +
                "        }" +
                "    }" +
                "});" +
                "return duePayments;" +
                "})();", duePayments -> {
            if (duePayments.equals("true")) {
                sharedPreferences.edit().putBoolean("duePayments", true).apply();
            } else {
                sharedPreferences.edit().remove("duePayments").apply();

                if (duePayments.equals("null")) {
                    errorHandler.error("1403");
                }
            }

            finishUp();
        });
    }

    /*
        Closing the database & dialog, storing the last refreshed date & time,
        clearing cache and finally signing the user in
     */
    public void finishUp() {
        compress();
        loading.setVisibility(View.VISIBLE);
        sharedPreferences.edit().putBoolean("isSignedIn", true).apply();
        myDatabase.close();

        webView.clearCache(true);
        webView.clearHistory();
        CookieManager.getInstance().removeAllCookies(null);

        try {
            Calendar c = Calendar.getInstance();
            SimpleDateFormat date = new SimpleDateFormat("MMM d", Locale.ENGLISH);
            SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.ENGLISH);
            sharedPreferences.edit().putString("refreshedDate", date.format(c.getTime())).apply();
            sharedPreferences.edit().putString("refreshedTime", time.format(c.getTime())).apply();
        } catch (Exception ignored) {
        }

        Intent intent = new Intent(context, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        downloadDialog.dismiss();
        context.startActivity(intent);
        ((Activity) context).finish();
    }

    /*
        Function to update the progress of the download. If more data needs to be downloaded,
        the max value can simply be updated here (SHOULD ALSO BE UPDATED IN dialog_download.xml)
     */
    private void updateProgress() {
        if (terminateDownload) {
            return;
        }

        if (Build.VERSION.SDK_INT >= 24) {
            progressBar.setProgress(++lastDownload, true);
        } else {
            progressBar.setProgress(++lastDownload);
        }

        String progress = lastDownload + " / 14";
        progressText.setText(progress);
    }

    /*
        Get the last successful download so that it can continue from
        where it left off instead of starting all over
     */
    public int getLastDownload() {
        return lastDownload;
    }

    /*
        Function to terminate the download process
     */
    public void terminateDownload() {
        terminateDownload = true;
    }

    /*
        Function to display an error message
     */
    public void error(final int errorCode) {
        if (terminateDownload) {
            return;
        }

        errorHandler.error(String.valueOf(errorCode));

        ((Activity) context).runOnUiThread(() -> {
            counter = 0;
            reloadPage();
        });
    }
}
