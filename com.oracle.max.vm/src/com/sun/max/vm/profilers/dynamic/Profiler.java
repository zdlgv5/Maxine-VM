/*
 * Copyright (c) 2017, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package com.sun.max.vm.profilers.dynamic;

import com.sun.max.unsafe.Size;
import com.sun.max.vm.Log;
import com.sun.max.vm.MaxineVM;
import com.sun.max.vm.thread.VmThread;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Profiler {

    /**
     * Histogram: the data structure that stores the profiling outcome.
     * The bins/buckets (the keys of the HashMap) contain Long type object sizes.
     * The values of the HashMap contain the sum of the equal-sized objects have been profiled so far.
     */
    public Map<Long, Integer> histogram;

    /**
     * A Concurrent HashMap is used for the histogram (flexible and thread-safe).
     */
    public Profiler() {
        histogram = new ConcurrentHashMap<Long, Integer>();
    }


    /**
     * Updates the histogram with the size of the profiled object.
     * If that size has never been met again, a new bin/bucket is inserted.
     * Else, the value of the corresponding bin/bucket is incremented.
     *
     * @param size
     */
    public void record(Long size) {
        if (!histogram.containsKey(size)) {
            histogram.put(size, 1);
        } else {
            histogram.put(size, histogram.get(size) + 1);
        }

    }

    /**
     * This method is called when a profiled object is allocated.
     */
    public void profile(Long size) {
        if (VmThread.current().PROFILE) {
            if (MaxineVM.isRunning()) {
                record(size);
            }
        }
    }

    /**
     * Dump the histogram to Maxine's Log output.
     */
    public void dumpHistogram() {
        Map<Long, Integer> map = new TreeMap<Long, Integer>(histogram);
        Set set2 = map.entrySet();
        Iterator iterator2 = set2.iterator();
        while (iterator2.hasNext()) {
            Map.Entry me2 = (Map.Entry) iterator2.next();
            Log.print(me2.getKey() + ": ");
            Log.println(me2.getValue());
        }

    }
}
