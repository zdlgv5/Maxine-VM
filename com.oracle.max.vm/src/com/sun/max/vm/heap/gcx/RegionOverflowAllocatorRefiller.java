/*
 * Copyright (c) 2012, 2012, Oracle and/or its affiliates. All rights reserved.
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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.sun.max.vm.heap.gcx;

import static com.sun.max.vm.heap.gcx.HeapRegionConstants.*;
import static com.sun.max.vm.heap.gcx.HeapRegionInfo.*;
import static com.sun.max.vm.heap.gcx.HeapRegionState.*;

import com.sun.max.annotate.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.heap.*;
import com.sun.max.vm.runtime.*;

/**
 * Refill manager for an overflow allocator layer on top of regions.
 * An allocator is refilled with a single large chunk of memory of a minimum size.
 */
final public class RegionOverflowAllocatorRefiller extends Refiller {
    private static final OutOfMemoryError outOfMemoryError = new OutOfMemoryError();
    /**
     * Region providing space for refilling allocator.
     */
    private int allocatingRegion;

    /**
     * Provider of regions.
     */
    private RegionProvider regionProvider;

    /**
     * Minimum amount of space to refill an overflow allocator with.
     */
    private Size minRefillSize;
    /**
     * Count of  wasted space from retired regions returned to the region provider.
     */
    private Size retiredWaste;
    /**
     * Count of free space  from retired regions returned to the region provider.
     */
    private Size retiredFreeSpace;

    private boolean traceRefill = false;

    public Object refillLock() {
        return regionProvider;
    }

    public int allocatingRegion() {
        return allocatingRegion;
    }

    public void setTraceRefill(boolean flag) {
        traceRefill = flag;
    }

    @INLINE
    private boolean traceRefill() {
        return MaxineVM.isDebug() && traceRefill;
    }

    RegionOverflowAllocatorRefiller(RegionProvider regionProvider) {
        this.allocatingRegion = INVALID_REGION_ID;
        this.regionProvider = regionProvider;
    }

    public void setMinRefillSize(Size minRefillSize) {
        this.minRefillSize = minRefillSize;
    }

    @Override
    public Address allocateRefill(Pointer startOfSpaceLeft, Size spaceLeft) {
        return overflowRefill(startOfSpaceLeft, spaceLeft);
    }

    @Override
    protected void doBeforeGC() {
        final int regionID = allocatingRegion;
        if (regionID != INVALID_REGION_ID) {
            allocatingRegion = INVALID_REGION_ID;
            // we're going to GC the owner of the allocating region.
            // The allocator this refiller manages will take care of making it parsable.
            // Free space doesn't matter, GC will re-organize it any, so just put it in the full state.
            toFullState(fromRegionID(regionID));
            regionProvider.retireAllocatingRegion(regionID);
        }
    }

    static private void checkForSuspisciousGC(int gcCount) {
        if (gcCount > 1) {
            FatalError.breakpoint();
        }
        if (gcCount > 5) {
            FatalError.unexpected("Suspiscious repeating GC calls detected");
        }
    }

    /**
     * Retire the current allocating region. Depending on the space left in the region by the allocator managed by this refiller, the
     * region is set in the full state or in the free chunk state.
     * @param startOfSpaceLeft address of the chunk of space left in the region
     * @param spaceLeft size of the chunk of space left in the region
     */
    private void retireAllocatingRegion(Pointer startOfSpaceLeft, Size spaceLeft) {
        if (allocatingRegion != INVALID_REGION_ID) {
            final HeapRegionInfo regionInfo = fromRegionID(allocatingRegion);
            if (MaxineVM.isDebug() && regionInfo.hasFreeChunks()) {
                regionInfo.dump(true);
                FatalError.unexpected("must not have any free chunks");
            }
            if (spaceLeft.greaterEqual(regionProvider.minRetiredFreeChunkSize())) {
                if (traceRefill()) {
                    final boolean lockDisabledSafepoints = Log.lock();
                    Log.print("overflow allocator putback region #");
                    Log.print(allocatingRegion);
                    Log.print(" in TLAB allocation list with ");
                    Log.print(spaceLeft.toInt());
                    Log.println(" bytes");
                    Log.unlock(lockDisabledSafepoints);
                }
                retiredFreeSpace = retiredFreeSpace.plus(spaceLeft);
                HeapFreeChunk.format(startOfSpaceLeft, spaceLeft);
                regionInfo.setFreeChunks(startOfSpaceLeft, spaceLeft, 1);
                toFreeChunkState(regionInfo);
            } else {
                if (traceRefill()) {
                    final boolean lockDisabledSafepoints = Log.lock();
                    Log.print("overflow allocator full region #");
                    Log.println(allocatingRegion);
                    Log.unlock(lockDisabledSafepoints);
                }
                // Just make the space left parsable.
                if (!spaceLeft.isZero()) {
                    retiredWaste = retiredWaste.plus(spaceLeft);
                    HeapSchemeAdaptor.fillWithDeadObject(startOfSpaceLeft, startOfSpaceLeft.plus(spaceLeft));
                }
                toFullState(regionInfo);
            }
            regionProvider.retireAllocatingRegion(allocatingRegion);
            allocatingRegion = INVALID_REGION_ID;
        }
    }

    /**
     * Get a region with enough space for an overflow allocator refill from the region provider and set
     * it as the allocating region.
     *
     * @return a HeapRegionInfo for convenience.
     */
    private HeapRegionInfo getAllocatingRegion() {
        int gcCount = 0;
        do {
            allocatingRegion = regionProvider.getAllocatingRegion(minRefillSize, 1);
            if (allocatingRegion != INVALID_REGION_ID) {
                if (traceRefill()) {
                    Log.print("Refill overflow allocator w/ region #");
                    Log.println(allocatingRegion);
                }
                return  fromRegionID(allocatingRegion);
            }
            if (MaxineVM.isDebug()) {
                checkForSuspisciousGC(gcCount++);
            }
        } while(Heap.collectGarbage(Size.fromInt(regionSizeInBytes))); // Always collect for at least one region.
        // Not enough freed memory.
        throw outOfMemoryError;
    }
    /**
     * Try to refill the overflow allocator with a single continuous chunk. Runs GC if can't.
     * @param minRefillSize minimum amount of space to refill the allocator with
     * @return address to a chunk of the requested size, or zero if none requested.
     */
    private Address overflowRefill(Pointer startOfSpaceLeft, Size spaceLeft) {
        synchronized (refillLock()) {
            retireAllocatingRegion(startOfSpaceLeft, spaceLeft);
            final HeapRegionInfo regionInfo = getAllocatingRegion();

            Address refill = Address.zero();
            if (regionInfo.isEmpty()) {
                refill =  regionInfo.regionStart();
                HeapFreeChunk.format(refill, Size.fromInt(regionSizeInBytes));
            } else {
                refill = regionInfo.firstFreeBytes();
                regionInfo.clearFreeChunks();
            }
            toAllocatingState(regionInfo);
            return refill;
        }
    }

}
