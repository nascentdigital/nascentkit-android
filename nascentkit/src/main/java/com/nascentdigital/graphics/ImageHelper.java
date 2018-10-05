package com.nascentdigital.graphics;


import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;

import java.nio.ByteBuffer;


public final class ImageHelper {

    private ImageHelper() {
    }

    public static Bitmap createBitmap(Image image) {

        // fail if the image data isn't single-planed
        Image.Plane[] planes = image.getPlanes();
        if (planes.length != 1) {
            throw new UnsupportedOperationException(
                "Only single-plane images are supported.");
        }

        // get mapped buffer if any
        ByteBuffer imageBuffer = planes[0].getBuffer();
        byte[] imageData;
        if (imageBuffer.hasArray()) {
            imageData = imageBuffer.array();
        }

        // or copy to buffer array
        else {
            imageData = new byte[imageBuffer.remaining()];
            imageBuffer.get(imageData);
        }

        // create bitmap from data and return it
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageData,
            0, imageData.length);
        return bitmap;
    }
}

