package com.imagepicker.media;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.net.Uri;
import android.webkit.MimeTypeMap;

import com.facebook.react.bridge.ReadableMap;

import java.io.File;

/**
 * Created by rusfearuth on 15.03.17.
 */

public class ImageConfig
{
    public final int maxWidth;
    public final int maxHeight;
    public final int quality;
    public final int rotation;
    public final boolean saveToCameraRoll;

    public ImageConfig(final int maxWidth,
                       final int maxHeight,
                       final int quality,
                       final int rotation,
                       final boolean saveToCameraRoll)
    {
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.quality = quality;
        this.rotation = rotation;
        this.saveToCameraRoll = saveToCameraRoll;
    }

    public @NonNull ImageConfig withMaxWidth(final int maxWidth)
    {
        return new ImageConfig(maxWidth,
                this.maxHeight, this.quality, this.rotation,
                this.saveToCameraRoll
        );
    }

    public @NonNull ImageConfig withMaxHeight(final int maxHeight)
    {
        return new ImageConfig(this.maxWidth,
                maxHeight, this.quality, this.rotation,
                this.saveToCameraRoll
        );

    }

    public @NonNull ImageConfig withQuality(final int quality)
    {
        return new ImageConfig(this.maxWidth,
                this.maxHeight, quality, this.rotation,
                this.saveToCameraRoll
        );
    }

    public @NonNull ImageConfig withRotation(final int rotation)
    {
        return new ImageConfig(this.maxWidth,
                this.maxHeight, this.quality, rotation,
                this.saveToCameraRoll
        );
    }

    public @NonNull ImageConfig withUri(@Nullable final Uri uri)
    {
        if (uri!= null) {
            //if it is a GIF file, always set quality to 100 to prevent compression
            String extension = MimeTypeMap.getFileExtensionFromUrl(uri.getPath());
            int quality = this.quality;
            if(extension.contains("gif")){
                quality = 100;
            }
        }

        return new ImageConfig(this.maxWidth,
                this.maxHeight, quality, this.rotation,
                this.saveToCameraRoll
        );
    }


    public @NonNull ImageConfig withSaveToCameraRoll(@Nullable final boolean saveToCameraRoll)
    {
        return new ImageConfig(
                this.maxWidth,
                this.maxHeight, this.quality, this.rotation,
                saveToCameraRoll
        );
    }

    public @NonNull ImageConfig updateFromOptions(@NonNull final ReadableMap options)
    {
        int maxWidth = 0;
        if (options.hasKey("maxWidth"))
        {
            maxWidth = (int) options.getDouble("maxWidth");
        }
        int maxHeight = 0;
        if (options.hasKey("maxHeight"))
        {
            maxHeight = (int) options.getDouble("maxHeight");
        }
        int quality = 100;
        if (options.hasKey("quality"))
        {
            quality = (int) (options.getDouble("quality") * 100);
        }
        int rotation = 0;
        if (options.hasKey("rotation"))
        {
            rotation = (int) options.getDouble("rotation");
        }
        boolean saveToCameraRoll = false;
        if (options.hasKey("storageOptions"))
        {
            final ReadableMap storageOptions = options.getMap("storageOptions");
            if (storageOptions.hasKey("cameraRoll"))
            {
                saveToCameraRoll = storageOptions.getBoolean("cameraRoll");
            }
        }
        return new ImageConfig(maxWidth, maxHeight, quality, rotation, saveToCameraRoll);
    }

    public boolean useOriginal(int initialWidth,
                               int initialHeight,
                               int currentRotation)
    {
        return ((initialWidth < maxWidth && maxWidth > 0) || maxWidth == 0) &&
                ((initialHeight < maxHeight && maxHeight > 0) || maxHeight == 0) &&
                quality == 100 && (rotation == 0 || currentRotation == rotation);
    }
}
