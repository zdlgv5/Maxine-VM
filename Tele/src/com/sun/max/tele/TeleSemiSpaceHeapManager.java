/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package com.sun.max.tele;

import com.sun.max.tele.debug.*;
import com.sun.max.tele.object.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.runtime.*;

/**
 *
 * @author Hannes Payer
 *
 */
public final class TeleSemiSpaceHeapManager extends TeleHeapManager{

    private TeleSemiSpaceHeapManager(TeleVM teleVM) {
        super(teleVM);
    }

    public static TeleHeapManager make(TeleVM teleVM) {
        if (teleHeapManager ==  null) {
            teleHeapManager = new TeleSemiSpaceHeapManager(teleVM);
        }
        return teleHeapManager;
    }

    @Override
    public boolean isInLiveMemory(Address address) {

        if (teleVM().isInGC()) { // this assumption needs to be proofed; basically it means that during GC both heaps are valid
            return true;
        }

        for (TeleRuntimeMemoryRegion teleHeapRegion : teleHeapRegions) {
            if (teleHeapRegion.contains(address)) {
                if (teleHeapRegion.description().equals("Heap-From")) { // everything in from-space is dead
                    return false;
                }
                if (address.greaterEqual(teleHeapRegion.mark())) { // everything in to-space after the global allocation mark is dead
                    return false;
                }
                for (TeleNativeThread teleNativeThread : teleVM().threads()) { // iterate over threads in check in case of tlabs if objects are dead or live
                    TeleThreadLocalValues teleThreadLocalValues = teleNativeThread.threadLocalsFor(Safepoint.State.ENABLED);
                    if (!teleThreadLocalValues.getWord("_TLAB_DISABLED").equals(Word.zero())) {
                        if (address.greaterEqual(teleThreadLocalValues.getWord("_TLAB_MARK").asAddress()) && teleThreadLocalValues.getWord("_TLAB_TOP").asAddress().greaterThan(address)) {
                            return false;
                        }
                    }
                }
                return true;
            }
        }
        return true;
    }

}
