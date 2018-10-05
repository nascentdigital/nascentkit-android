package com.nascentdigital.util;

import android.util.Size;

import java.util.Comparator;


public final class SizeComparator implements Comparator<Size> {

    @Override
    public int compare(Size lhs, Size rhs) {

        // get sizes (cast to avoid overflow)
        long lhsArea = (long) lhs.getWidth() * lhs.getHeight();
        long rhsArea = (long) rhs.getWidth() * rhs.getHeight();

        // return size difference
        return Long.signum(lhsArea - rhsArea);
    }
}
