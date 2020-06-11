package com.imagepicker.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Environment;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.ReadableMap;
import com.imagepicker.ResponseHelper;
import com.imagepicker.media.ImageConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.UUID;

import static com.imagepicker.ImagePickerModule.REQUEST_LAUNCH_IMAGE_CAPTURE;

/**
 * Created by rusfearuth on 15.03.17.
 */

public class MediaUtils
{
    public static @Nullable File createNewFile(@NonNull final Context reactContext,
                                               @NonNull final ReadableMap options,
                                               @NonNull final boolean forceLocal)
    {
        final String filename = new StringBuilder("image-")
                .append(UUID.randomUUID().toString())
                .append(".jpg")
                .toString();

        // defaults to Public Pictures Directory
        File path = reactContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES);

        if (ReadableMapUtils.hasAndNotNullReadableMap(options, "storageOptions"))
        {
            final ReadableMap storageOptions = options.getMap("storageOptions");

            if (storageOptions.hasKey("privateDirectory"))
            {
                boolean saveToPrivateDirectory = storageOptions.getBoolean("privateDirectory");
                if (saveToPrivateDirectory)
                {
                    // if privateDirectory is set then save to app's private files directory
                    path = reactContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                }
            }

            if (ReadableMapUtils.hasAndNotEmptyString(storageOptions, "path"))
            {
                path = new File(path, storageOptions.getString("path"));
            }
        }
        else if (forceLocal)
        {
            path = reactContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        }

        File result = new File(path, filename);

        try
        {
            path.mkdirs();
            result.createNewFile();
        }
        catch (IOException e)
        {
            e.printStackTrace();
            result = null;
        }

        return result;
    }

    /**
     * Create a resized image to fulfill the maxWidth/maxHeight, quality and rotation values
     *
     * @param imageInputStream
     * @param imageConfig
     * @param initialWidth
     * @param initialHeight
     * @return updated ImageConfig
     */
    public static @NonNull Bitmap getResizedImage(@NonNull final InputStream imageInputStream,
                                                       @NonNull final ImageConfig imageConfig,
                                                       int initialWidth,
                                                       int initialHeight)
    {
        BitmapFactory.Options imageOptions = new BitmapFactory.Options();
        imageOptions.inScaled = false;
        imageOptions.inSampleSize = 1;

        if (imageConfig.maxWidth != 0 || imageConfig.maxHeight != 0) {
            while ((imageConfig.maxWidth == 0 || initialWidth > 2 * imageConfig.maxWidth) &&
                   (imageConfig.maxHeight == 0 || initialHeight > 2 * imageConfig.maxHeight)) {
                imageOptions.inSampleSize *= 2;
                initialHeight /= 2;
                initialWidth /= 2;
            }
        }

        Bitmap photo = BitmapFactory.decodeStream(imageInputStream, null, imageOptions);

        if (photo == null)
        {
            return null;
        }

        ImageConfig result = imageConfig;

        Bitmap scaledPhoto = null;
        if (imageConfig.maxWidth == 0 || imageConfig.maxWidth > initialWidth)
        {
            result = result.withMaxWidth(initialWidth);
        }
        if (imageConfig.maxHeight == 0 || imageConfig.maxWidth > initialHeight)
        {
            result = result.withMaxHeight(initialHeight);
        }

        double widthRatio = (double) result.maxWidth / initialWidth;
        double heightRatio = (double) result.maxHeight / initialHeight;

        double ratio = (widthRatio < heightRatio)
                ? widthRatio
                : heightRatio;

        Matrix matrix = new Matrix();
        matrix.postRotate(result.rotation);
        matrix.postScale((float) ratio, (float) ratio);

        ExifInterface exif;
        try
        {
            imageInputStream.reset();
            exif = new ExifInterface(imageInputStream);

            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0);

            switch (orientation)
            {
                case 6:
                    matrix.postRotate(90);
                    break;
                case 3:
                    matrix.postRotate(180);
                    break;
                case 8:
                    matrix.postRotate(270);
                    break;
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }

        scaledPhoto = Bitmap.createBitmap(photo, 0, 0, photo.getWidth(), photo.getHeight(), matrix, true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        scaledPhoto.compress(Bitmap.CompressFormat.JPEG, result.quality, bytes);

        return scaledPhoto;

    }


    public static ReadExifResult readExifInterface(@NonNull InputStream inputStream, @NonNull ResponseHelper responseHelper)
    {
        ReadExifResult result;
        int currentRotation = 0;

        try
        {
            ExifInterface exif = new ExifInterface(inputStream);

            // extract lat, long, and timestamp and add to the response
            float[] latlng = new float[2];
            exif.getLatLong(latlng);
            float latitude = latlng[0];
            float longitude = latlng[1];
            if(latitude != 0f || longitude != 0f)
            {
                responseHelper.putDouble("latitude", latitude);
                responseHelper.putDouble("longitude", longitude);
            }

            final String timestamp = exif.getAttribute(ExifInterface.TAG_DATETIME);
            final SimpleDateFormat exifDatetimeFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");

            final DateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

            try
            {
                final String isoFormatString = new StringBuilder(isoFormat.format(exifDatetimeFormat.parse(timestamp)))
                        .append("Z").toString();
                responseHelper.putString("timestamp", isoFormatString);
            }
            catch (Exception e) {}

            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            boolean isVertical = true;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_270:
                    isVertical = false;
                    currentRotation = 270;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_90:
                    isVertical = false;
                    currentRotation = 90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    currentRotation = 180;
                    break;
            }
            responseHelper.putInt("originalRotation", currentRotation);
            responseHelper.putBoolean("isVertical", isVertical);
            result = new ReadExifResult(currentRotation, null);
        }
        catch (IOException e)
        {
            e.printStackTrace();
            result = new ReadExifResult(currentRotation, e);
        }

        return result;
    }

    public static class ReadExifResult
    {
        public final int currentRotation;
        public final Throwable error;

        public ReadExifResult(int currentRotation,
                              @Nullable final Throwable error)
        {
            this.currentRotation = currentRotation;
            this.error = error;
        }
    }
}
