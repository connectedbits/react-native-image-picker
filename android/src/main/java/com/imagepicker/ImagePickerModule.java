package com.imagepicker;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;
import androidx.annotation.NonNull;
import androidx.annotation.StyleRes;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.app.AlertDialog;

import android.util.Base64;
import android.content.pm.PackageManager;
import android.util.Base64OutputStream;

import com.facebook.react.ReactActivity;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.module.annotations.ReactModule;
import com.imagepicker.media.ImageConfig;
import com.imagepicker.permissions.PermissionUtils;
import com.imagepicker.permissions.OnImagePickerPermissionsCallback;
import com.imagepicker.utils.MediaUtils.ReadExifResult;
import com.imagepicker.utils.ReadableMapUtils;
import com.imagepicker.utils.RealPathUtil;
import com.imagepicker.utils.UI;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.util.List;

import com.facebook.react.modules.core.PermissionListener;
import com.facebook.react.modules.core.PermissionAwareActivity;

import static com.imagepicker.utils.MediaUtils.*;
import static com.imagepicker.utils.MediaUtils.createNewFile;
import static com.imagepicker.utils.MediaUtils.getResizedImage;

@ReactModule(name = ImagePickerModule.NAME)
public class ImagePickerModule extends ReactContextBaseJavaModule
        implements ActivityEventListener
{
  public static final String NAME = "ImagePickerManager";

  public static final int DEFAULT_EXPLAINING_PERMISSION_DIALIOG_THEME = R.style.DefaultExplainingPermissionsTheme;

  public static final int REQUEST_LAUNCH_IMAGE_CAPTURE    = 13001;
  public static final int REQUEST_LAUNCH_IMAGE_LIBRARY    = 13002;
  public static final int REQUEST_LAUNCH_VIDEO_LIBRARY    = 13003;
  public static final int REQUEST_LAUNCH_VIDEO_CAPTURE    = 13004;
  public static final int REQUEST_PERMISSIONS_FOR_CAMERA  = 14001;
  public static final int REQUEST_PERMISSIONS_FOR_LIBRARY = 14002;

  private final ReactApplicationContext reactContext;
  private final int dialogThemeId;

  protected Callback callback;
  private Callback permissionRequestCallback;

  private ReadableMap options;
  protected Uri cameraCaptureURI;
  private Boolean noData = false;
  private Boolean pickVideo = false;
  private Boolean pickBoth = false;
  private ImageConfig imageConfig = new ImageConfig(0, 0, 100, 0, false);

  @Deprecated
  private int videoQuality = 1;

  @Deprecated
  private int videoDurationLimit = 0;

  private ResponseHelper responseHelper = new ResponseHelper();
  private PermissionListener listener = new PermissionListener()
  {
    public boolean onRequestPermissionsResult(final int requestCode,
                                              @NonNull final String[] permissions,
                                              @NonNull final int[] grantResults)
    {
      boolean permissionsGranted = true;
      for (int i = 0; i < permissions.length; i++)
      {
        final boolean granted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
        permissionsGranted = permissionsGranted && granted;
      }

      if (callback == null || options == null)
      {
        return false;
      }

      if (!permissionsGranted)
      {
        responseHelper.invokeError(permissionRequestCallback, "Permissions weren't granted");
        return false;
      }

      switch (requestCode)
      {
        case REQUEST_PERMISSIONS_FOR_CAMERA:
          launchCamera(options, permissionRequestCallback);
          break;

        case REQUEST_PERMISSIONS_FOR_LIBRARY:
          launchImageLibrary(options, permissionRequestCallback);
          break;

      }
      return true;
    }
  };

  public ImagePickerModule(ReactApplicationContext reactContext)
  {
    this(reactContext, DEFAULT_EXPLAINING_PERMISSION_DIALIOG_THEME);
  }

  public ImagePickerModule(ReactApplicationContext reactContext,
                           @StyleRes final int dialogThemeId)
  {
    super(reactContext);

    this.dialogThemeId = dialogThemeId;
    this.reactContext = reactContext;
    this.reactContext.addActivityEventListener(this);
  }

  @Override
  public String getName() {
    return NAME;
  }

  @ReactMethod
  public void showImagePicker(final ReadableMap options, final Callback callback) {
    Activity currentActivity = getCurrentActivity();

    if (currentActivity == null)
    {
      responseHelper.invokeError(callback, "can't find current Activity");
      return;
    }

    this.callback = callback;
    this.options = options;
    imageConfig = new ImageConfig(0, 0, 100, 0, false);

    final AlertDialog dialog = UI.chooseDialog(this, options, new UI.OnAction()
    {
      @Override
      public void onTakePhoto(@NonNull final ImagePickerModule module)
      {
        if (module == null)
        {
          return;
        }
        module.launchCamera();
      }

      @Override
      public void onUseLibrary(@NonNull final ImagePickerModule module)
      {
        if (module == null)
        {
          return;
        }
        module.launchImageLibrary();
      }

      @Override
      public void onCancel(@NonNull final ImagePickerModule module)
      {
        if (module == null)
        {
          return;
        }
        module.doOnCancel();
      }

      @Override
      public void onCustomButton(@NonNull final ImagePickerModule module,
                                 @NonNull final String action)
      {
        if (module == null)
        {
          return;
        }
        module.invokeCustomButton(action);
      }
    });
    dialog.show();
  }

  public void doOnCancel()
  {
    if (callback != null) {
      responseHelper.invokeCancel(callback);
      callback = null;
    }
  }

  public void launchCamera()
  {
    this.launchCamera(this.options, this.callback);
  }

  // NOTE: Currently not reentrant / doesn't support concurrent requests
  @ReactMethod
  public void launchCamera(final ReadableMap options, final Callback callback)
  {
    try {
      permissionRequestCallback = callback;

      if (!isCameraAvailable())
      {
        responseHelper.invokeError(callback, "Camera not available");
        return;
      }

      final Activity currentActivity = getCurrentActivity();
      if (currentActivity == null)
      {
        responseHelper.invokeError(callback, "can't find current Activity");
        return;
      }

      this.callback = callback;
      this.options = options;
      try {
        if (!permissionsCheck(currentActivity, REQUEST_PERMISSIONS_FOR_CAMERA))
        {
          return;
        }
      } catch(Exception ex){
        if(callback != null){
          responseHelper.invokeError(callback, "Error checking permissions, " + ex.getLocalizedMessage());
        }
      }


      parseOptions(this.options);

      int requestCode;
      Intent cameraIntent;

      if (pickVideo)
      {
        requestCode = REQUEST_LAUNCH_VIDEO_CAPTURE;
        cameraIntent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        cameraIntent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, videoQuality);
        if (videoDurationLimit > 0)
        {
          cameraIntent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, videoDurationLimit);
        }
      }
      else
      {
        requestCode = REQUEST_LAUNCH_IMAGE_CAPTURE;
        cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

        final File original = createNewFile(reactContext, this.options, false);

        if (original != null) {
          cameraCaptureURI = RealPathUtil.compatUriFromFile(reactContext, original);
        }else {
          responseHelper.invokeError(callback, "Couldn't get file path for photo");
          return;
        }
        if (cameraCaptureURI == null)
        {
          responseHelper.invokeError(callback, "Couldn't get file path for photo");
          return;
        }
        cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraCaptureURI);
      }

      if (cameraIntent.resolveActivity(reactContext.getPackageManager()) == null)
      {
        responseHelper.invokeError(callback, "Cannot launch camera");
        return;
      }

      // Workaround for Android bug.
      // grantUriPermission also needed for KITKAT,
      // see https://code.google.com/p/android/issues/detail?id=76683
      if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) {
        List<ResolveInfo> resInfoList = reactContext.getPackageManager().queryIntentActivities(cameraIntent, PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo resolveInfo : resInfoList) {
          if(resolveInfo.activityInfo != null){
            String packageName = resolveInfo.activityInfo.packageName;
            reactContext.grantUriPermission(packageName, cameraCaptureURI, Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
          }
        }
      }

      try
      {
        currentActivity.startActivityForResult(cameraIntent, requestCode);
      }
      catch (ActivityNotFoundException e)
      {
        e.printStackTrace();
        responseHelper.invokeError(callback, "Cannot launch camera, activity not found, " + e.getLocalizedMessage());
      }
    } catch(Exception ex){
      if(callback != null){
        responseHelper.invokeError(callback, "Cannot launch camera, " + ex.getLocalizedMessage());
      }
    }

  }

  public void launchImageLibrary()
  {
    this.launchImageLibrary(this.options, this.callback);
  }
  // NOTE: Currently not reentrant / doesn't support concurrent requests
  @ReactMethod
  public void launchImageLibrary(final ReadableMap options, final Callback callback)
  {
    try {
      permissionRequestCallback = callback;

      final Activity currentActivity = getCurrentActivity();
      if (currentActivity == null) {
        responseHelper.invokeError(callback, "can't find current Activity");
        return;
      }

      this.callback = callback;
      this.options = options;

      try {
        if (!permissionsCheck(currentActivity, REQUEST_PERMISSIONS_FOR_LIBRARY))
        {
          return;
        }
      } catch(Exception ex){
        if(callback != null){
          responseHelper.invokeError(callback, "Error checking permissions, " + ex.getLocalizedMessage());
        }
      }

      parseOptions(this.options);

      int requestCode;
      Intent libraryIntent;
      if (pickVideo)
      {
        requestCode = REQUEST_LAUNCH_VIDEO_LIBRARY;
        libraryIntent = new Intent(Intent.ACTION_GET_CONTENT);
        libraryIntent.setType("video/*");
        libraryIntent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        libraryIntent.putExtra(Intent.CATEGORY_OPENABLE, true);
      }
      else
      {
        requestCode = REQUEST_LAUNCH_IMAGE_LIBRARY;
        libraryIntent = new Intent(Intent.ACTION_GET_CONTENT);
        libraryIntent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        libraryIntent.putExtra(Intent.CATEGORY_OPENABLE, true);

        if (pickBoth)
        {
          libraryIntent.setType("image/* video/*");
        } else {
          libraryIntent.setType("image/*");
        }
      }

      if (libraryIntent.resolveActivity(reactContext.getPackageManager()) == null)
      {
        responseHelper.invokeError(callback, "Cannot launch photo library");
        return;
      }

      try
      {
        String chooseWhichLibraryTitle = null;
        if (ReadableMapUtils.hasAndNotEmptyString(options, "chooseWhichLibraryTitle"))
        {
          chooseWhichLibraryTitle = options.getString("chooseWhichLibraryTitle");
        }

        currentActivity.startActivityForResult(Intent.createChooser(libraryIntent, chooseWhichLibraryTitle), requestCode);
      }
      catch (ActivityNotFoundException e)
      {
        e.printStackTrace();
        responseHelper.invokeError(callback, "Cannot launch photo library, activity not found, " + e.getLocalizedMessage());
      }
    } catch(Exception ex){
      if(callback != null){
        responseHelper.invokeError(callback, "Cannot launch photo library, " + ex.getLocalizedMessage());
      }
    }

  }

  @Override
  public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
    //robustness code
    if (passResult(requestCode))
    {
      return;
    }
    try {

      responseHelper.cleanResponse();

      // user cancel
      if (resultCode != Activity.RESULT_OK)
      {
        responseHelper.invokeCancel(callback);
        return;
      }

      Uri uri = null;
      switch (requestCode)
      {
        case REQUEST_LAUNCH_IMAGE_CAPTURE:
          uri = cameraCaptureURI;
          break;

        case REQUEST_LAUNCH_IMAGE_LIBRARY:
          uri = data.getData();
          if(uri == null){
            responseHelper.putString("error", "Could not read photo");
            responseHelper.invokeResponse(callback);
            return;
          }
          break;

        case REQUEST_LAUNCH_VIDEO_LIBRARY:
          responseHelper.putString("uri", data.getData().toString());
          responseHelper.invokeResponse(callback);
          return;

        case REQUEST_LAUNCH_VIDEO_CAPTURE:
          responseHelper.putString("uri", data.getData().toString());
          responseHelper.invokeResponse(callback);
          return;
      }

      ReadExifResult result = null;
      try {
        InputStream imageInputStream = reactContext.getContentResolver().openInputStream(uri);
        if(imageInputStream != null){
          result = readExifInterface(imageInputStream, responseHelper);
        }
      } catch(IOException ex){
        responseHelper.invokeError(callback, ex.getMessage());
        return;
      }
      if (result == null) {
        responseHelper.invokeError(callback, "Image could not be read");
        return;
      }
      if (result.error != null)
      {
        responseHelper.invokeError(callback, result.error.getMessage());
        return;
      }

      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inJustDecodeBounds = true;
      Bitmap imageBitmap = null;
      try {
        InputStream imageInputStream = reactContext.getContentResolver().openInputStream(uri);
        imageBitmap = BitmapFactory.decodeStream(imageInputStream, null, options);
      } catch(FileNotFoundException ex){
        responseHelper.invokeError(callback, "Could not find file");
        return;
      }


      int initialWidth = options.outWidth;
      int initialHeight = options.outHeight;


      // don't create a new file if contraint are respected
      if (imageConfig.useOriginal(initialWidth, initialHeight, result.currentRotation))
      {
        responseHelper.putInt("width", initialWidth);
        responseHelper.putInt("height", initialHeight);

        try {
          InputStream imageInputStream = reactContext.getContentResolver().openInputStream(uri);
          updatedResultResponse(imageInputStream);
        } catch(FileNotFoundException ex){
          responseHelper.invokeError(callback, "Could not find file");
          return;
        }
      }
      else
      {
        Bitmap resizedImage = null;
        ImageConfig rotatedImageConfig = imageConfig.withRotation(result.currentRotation);
        try {
          InputStream imageInputStream = reactContext.getContentResolver().openInputStream(uri);
          resizedImage = getResizedImage(imageInputStream, rotatedImageConfig, initialWidth, initialHeight);
        } catch(FileNotFoundException ex){
          responseHelper.invokeError(callback, "Could not find file");
          return;
        }

        if (resizedImage == null)
        {
          responseHelper.putString("error", "Can't resize the image");
        }
        else
        {
          responseHelper.putInt("width", resizedImage.getWidth());
          responseHelper.putInt("height", resizedImage.getHeight());
          ByteArrayOutputStream bos = new ByteArrayOutputStream();
          resizedImage.compress(Bitmap.CompressFormat.JPEG, imageConfig.quality, bos);
          byte[] bitmapdata = bos.toByteArray();
          ByteArrayInputStream bs = new ByteArrayInputStream(bitmapdata);
          updatedResultResponse(bs);
          resizedImage.recycle();
          resizedImage = null;
        }
      }
      if(imageBitmap != null){
        imageBitmap.recycle();
        imageBitmap = null;
      }

      if(callback != null){
        responseHelper.invokeResponse(callback);
      }
    } catch(Exception ex){
      if(callback != null){
        responseHelper.invokeError(callback, "Error in handling activity response, " + ex.getLocalizedMessage());
      }
    } finally {
      callback = null;
      options = null;
    }
  }

  public void invokeCustomButton(@NonNull final String action)
  {
    responseHelper.invokeCustomButton(callback, action);
  }

  @Override
  public void onNewIntent(Intent intent) { }

  public Context getContext()
  {
    return getReactApplicationContext();
  }

  public @StyleRes int getDialogThemeId()
  {
    return this.dialogThemeId;
  }

  public @NonNull Activity getActivity()
  {
    return getCurrentActivity();
  }


  private boolean passResult(int requestCode)
  {
    return callback == null || (cameraCaptureURI == null && requestCode == REQUEST_LAUNCH_IMAGE_CAPTURE)
            || (requestCode != REQUEST_LAUNCH_IMAGE_CAPTURE && requestCode != REQUEST_LAUNCH_IMAGE_LIBRARY
            && requestCode != REQUEST_LAUNCH_VIDEO_LIBRARY && requestCode != REQUEST_LAUNCH_VIDEO_CAPTURE);
  }

  private void updatedResultResponse(@NonNull final InputStream inputStream)
  {
      responseHelper.putString("data", getBase64StringFromInputStream(inputStream));
  }

  private boolean permissionsCheck(@NonNull final Activity activity,
                                   @NonNull final int requestCode)
  {
    final int writePermission = ActivityCompat
            .checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE);
    final int cameraPermission = ActivityCompat
            .checkSelfPermission(activity, Manifest.permission.CAMERA);

    boolean permissionsGranted = false;

    switch (requestCode) {
      case REQUEST_PERMISSIONS_FOR_LIBRARY:
        permissionsGranted = writePermission == PackageManager.PERMISSION_GRANTED;
        break;
      case REQUEST_PERMISSIONS_FOR_CAMERA:
        permissionsGranted = cameraPermission == PackageManager.PERMISSION_GRANTED && writePermission == PackageManager.PERMISSION_GRANTED;
        break;
    }

    if (!permissionsGranted)
    {
      final Boolean dontAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA);

      if (dontAskAgain)
      {
        final AlertDialog dialog = PermissionUtils
                .explainingDialog(this, options, new PermissionUtils.OnExplainingPermissionCallback()
                {
                  @Override
                  public void onCancel(WeakReference<ImagePickerModule> moduleInstance,
                                       DialogInterface dialogInterface)
                  {
                    final ImagePickerModule module = moduleInstance.get();
                    if (module == null)
                    {
                      return;
                    }
                    module.doOnCancel();
                  }

                  @Override
                  public void onReTry(WeakReference<ImagePickerModule> moduleInstance,
                                      DialogInterface dialogInterface)
                  {
                    final ImagePickerModule module = moduleInstance.get();
                    if (module == null)
                    {
                      return;
                    }
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    Uri uri = Uri.fromParts("package", module.getContext().getPackageName(), null);
                    intent.setData(uri);
                    final Activity innerActivity = module.getActivity();
                    if (innerActivity == null)
                    {
                      return;
                    }
                    innerActivity.startActivityForResult(intent, 1);
                  }
                });
        if (dialog != null) {
          dialog.show();
        }
        return false;
      }
      else
      {
        String[] PERMISSIONS;
        switch (requestCode) {
          case REQUEST_PERMISSIONS_FOR_LIBRARY:
            PERMISSIONS = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE};
            break;
          case REQUEST_PERMISSIONS_FOR_CAMERA:
            PERMISSIONS = new String[]{Manifest.permission.CAMERA,Manifest.permission.WRITE_EXTERNAL_STORAGE};
            break;
          default:
            PERMISSIONS = new String[]{};
            break;
        }

        if (activity instanceof ReactActivity)
        {
          ((ReactActivity) activity).requestPermissions(PERMISSIONS, requestCode, listener);
        }
        else if (activity instanceof PermissionAwareActivity) {
          ((PermissionAwareActivity) activity).requestPermissions(PERMISSIONS, requestCode, listener);
        }
        else if (activity instanceof OnImagePickerPermissionsCallback)
        {
          ((OnImagePickerPermissionsCallback) activity).setPermissionListener(listener);
          ActivityCompat.requestPermissions(activity, PERMISSIONS, requestCode);
        }
        else
        {
          final String errorDescription = new StringBuilder(activity.getClass().getSimpleName())
                  .append(" must implement ")
                  .append(OnImagePickerPermissionsCallback.class.getSimpleName())
                  .append(" or ")
                  .append(PermissionAwareActivity.class.getSimpleName())
                  .toString();
          throw new UnsupportedOperationException(errorDescription);
        }
        return false;
      }
    }
    return true;
  }

  private boolean isCameraAvailable() {
    return reactContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)
      || reactContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
  }

  private String getBase64StringFromInputStream(InputStream inputStream) {
    byte[] bytes;
    byte[] buffer = new byte[8192];
    int bytesRead;

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    Base64OutputStream base64Output = new Base64OutputStream(output, Base64.DEFAULT | Base64.NO_WRAP);

    try {
      while ((bytesRead = inputStream.read(buffer)) != -1) {
        base64Output.write(buffer, 0, bytesRead);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }


    try {
      base64Output.close();
      bytes = output.toByteArray();
      return new String(bytes, "UTF-8");
    } catch(UnsupportedEncodingException uex){
      return null;
    } catch(IOException ioex){
      return null;
    }
  }

  private void parseOptions(final ReadableMap options) {
    noData = false;
    if (options.hasKey("noData")) {
      noData = options.getBoolean("noData");
    }
    imageConfig = imageConfig.updateFromOptions(options);
    pickVideo = false;
    pickBoth = false;
    if (options.hasKey("mediaType") && options.getString("mediaType").equals("mixed")) {
      pickBoth = true;
    }
    if (options.hasKey("mediaType") && options.getString("mediaType").equals("video")) {
      pickVideo = true;
    }
    videoQuality = 1;
    if (options.hasKey("videoQuality") && options.getString("videoQuality").equals("low")) {
      videoQuality = 0;
    }
    videoDurationLimit = 0;
    if (options.hasKey("durationLimit")) {
      videoDurationLimit = options.getInt("durationLimit");
    }
  }
}
