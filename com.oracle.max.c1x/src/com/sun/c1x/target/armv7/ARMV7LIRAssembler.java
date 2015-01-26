/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.c1x.target.armv7;

import com.oracle.max.asm.Buffer;
import com.oracle.max.asm.Label;
import com.oracle.max.asm.NumUtil;
import com.oracle.max.asm.target.armv7.ARMV7;
import com.oracle.max.asm.target.armv7.ARMV7Assembler.ConditionFlag;
import com.oracle.max.asm.target.armv7.ARMV7MacroAssembler;
import com.oracle.max.criutils.TTY;
import com.sun.c1x.C1XCompilation;
import com.sun.c1x.C1XOptions;
import com.sun.c1x.asm.TargetMethodAssembler;
import com.sun.c1x.gen.LIRGenerator.DeoptimizationStub;
import com.sun.c1x.ir.BlockBegin;
import com.sun.c1x.ir.Condition;
import com.sun.c1x.ir.Infopoint;
import com.sun.c1x.lir.FrameMap.StackBlock;
import com.sun.c1x.lir.*;
import com.sun.c1x.stub.CompilerStub;
import com.sun.c1x.util.Util;
import com.sun.cri.ci.*;
import com.sun.cri.ci.CiTargetMethod.JumpTable;
import com.sun.cri.ci.CiTargetMethod.Mark;
import com.sun.cri.xir.CiXirAssembler;
import com.sun.cri.xir.CiXirAssembler.RuntimeCallInformation;
import com.sun.cri.xir.CiXirAssembler.XirInstruction;
import com.sun.cri.xir.CiXirAssembler.XirLabel;
import com.sun.cri.xir.CiXirAssembler.XirMark;
import com.sun.cri.xir.XirSnippet;
import com.sun.cri.xir.XirTemplate;
import com.sun.max.vm.compiler.CompilationBroker;

import java.io.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.sun.cri.ci.CiCallingConvention.Type.RuntimeCall;
import static com.sun.cri.ci.CiValue.IllegalValue;

/**
 * This class implements the x86-specific code generation for LIR.
 */
public final class ARMV7LIRAssembler extends LIRAssembler {

    public static AtomicInteger methodCounter = new AtomicInteger(536870912);
    private static final Object fileLock = new Object();
    public static boolean DEBUG_METHODS;
    public static boolean DEBUG_MOVS;

    private static File file;
    private static final Object[] NO_PARAMS = new Object[0];
    private static final CiRegister SHIFTCount = ARMV7.r8;

    private static final long DoubleSignMask = 0x7FFFFFFFFFFFFFFFL;

    final CiTarget target;
    final ARMV7MacroAssembler masm;
    final CiRegister rscratch1;

    static {
        initDebugMethods();
    }

    public ARMV7LIRAssembler(C1XCompilation compilation, TargetMethodAssembler tasm) {
        super(compilation, tasm);
        masm = (ARMV7MacroAssembler) tasm.asm;
        target = compilation.target;
        rscratch1 = compilation.registerConfig.getScratchRegister();
    }

    public static void initDebugMethods() {
        String value = System.getenv("DEBUG_METHODS");
        if (value == null || value.isEmpty()) {
            DEBUG_METHODS = false;
        } else {
            DEBUG_METHODS = Integer.parseInt(value) == 1 ? true : false;
        }
        value = System.getenv("DEBUG_MOVS");
        if (value == null || value.isEmpty()) {
            DEBUG_MOVS = false;
        } else {
            DEBUG_MOVS = Integer.parseInt(value) == 1 ? true : false;
        }

        if ((file = new File(getDebugMethodsPath() + "debug_methods")).exists()) {
            file.delete();
        }
        if (DEBUG_METHODS) {
            file = new File(getDebugMethodsPath() + "debug_methods");
        }
    }

    public static void writeDebugMethod(String name, int index) throws Exception {
        synchronized (fileLock) {
            try {
                assert DEBUG_METHODS;
                FileWriter fw = new FileWriter(file.getAbsoluteFile(), true);
                BufferedWriter bw = new BufferedWriter(fw);
                bw.write(index + " " + name + "\n");
                bw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    }

    public static String getDebugMethodsPath() {
        return System.getenv("MAXINE_HOME") + "/maxine-tester/junit-tests/";

    }
    private CiAddress asAddress(CiValue value) {
        if (value.isAddress()) {
            return (CiAddress) value;
        }
        assert value.isStackSlot();
        return compilation.frameMap().toStackAddress((CiStackSlot) value);
    }

    @Override
    protected void emitOsrEntry() {
        throw Util.unimplemented();
    }

    @Override
    protected int initialFrameSizeInBytes() {
        return frameMap.frameSize();
    }

    @Override
    protected void emitReturn(CiValue result) {
        // TODO: Consider adding safepoint polling at return!

        masm.ret(0);
    }

    @Override
    protected void emitInfopoint(CiValue dst, LIRDebugInfo info, Infopoint.Op op) {
        switch (op) {
            case HERE:
                tasm.recordSafepoint(codePos(), info);
                int beforeLea = masm.codeBuffer.position();
                masm.leaq(dst.asRegister(), new CiAddress(target.wordKind, ARMV7.r15.asValue(), 0));
                int afterLea = masm.codeBuffer.position();
                masm.codeBuffer.setPosition(beforeLea);
                masm.leaq(dst.asRegister(), new CiAddress(target.wordKind, ARMV7.r15.asValue(), beforeLea - afterLea));
		// ADDED as meant to be 32bit quantity
        	//masm.mov32BitConstant(ARMV7.cpuRegisters[dst.asRegister().encoding+1], 0);

                break;
            case UNCOMMON_TRAP:
                directCall(CiRuntimeCall.Deoptimize, info);
                break;
            case INFO:
                tasm.recordSafepoint(codePos(), info);
                break;
            default:
                throw Util.shouldNotReachHere();
        }
    }

    @Override
    protected void emitMonitorAddress(int monitor, CiValue dst) {
        CiStackSlot slot = frameMap.toMonitorBaseStackAddress(monitor);
        masm.leaq(dst.asRegister(), new CiAddress(slot.kind, ARMV7.r13.asValue(),
            slot.index() * target.arch.wordSize));
    }

    @Override
    protected void emitPause() {
       masm.pause();
    }

    @Override
    protected void emitBreakpoint() {
       masm.int3();
    }

    @Override
    protected void emitIfBit(CiValue address, CiValue bitNo) {
        assert 0 == 1 : "emitIfBit ARMV7IRAssembler";

        // masm.btli((CiAddress) address, ((CiConstant) bitNo).asInt());
    }

    @Override
    protected void emitStackAllocate(StackBlock stackBlock, CiValue dst) {
        masm.leaq(dst.asRegister(), compilation.frameMap().toStackAddress(stackBlock));
    }

    private void moveRegs(CiRegister fromReg, CiRegister toReg) {
        if (fromReg != toReg) {
            masm.mov(ConditionFlag.Always, false, toReg, fromReg);

        }
    }

    private void moveRegs(CiRegister src, CiRegister dest, CiKind srcKind, CiKind destKind) {
        if (src != dest) {
            if (srcKind == CiKind.Long && destKind == CiKind.Long) {
                masm.mov(ConditionFlag.Always, false, dest, src);
                masm.mov(ConditionFlag.Always, false, ARMV7.cpuRegisters[dest.number+1], ARMV7.cpuRegisters[src.number+1]);
            } else  if (srcKind == CiKind.Int && destKind == CiKind.Long){
                masm.mov(ConditionFlag.Always, false, dest, src);
                masm.asr(ConditionFlag.Always, false, ARMV7.cpuRegisters[dest.number+1], src, 31);
            } else {
                masm.mov(ConditionFlag.Always, false, dest, src);
            }
        }
    }

    private void swapReg(CiRegister a, CiRegister b) {
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 9);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 9);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 9);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 9);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 9);
        }
        masm.xchgptr(a, b);
    }

    private void const2reg(CiRegister dst, int constant) {
        // Do not optimize with an XOR as this instruction may be between
        // a CMP and a Jcc in which case the XOR will modify the condition
        // flags and interfere with the Jcc.
        masm.mov32BitConstant(dst, constant);
    }

    private void const2reg(CiRegister dst, long constant) {
        // Do not optimize with an XOR as this instruction may be between
        // a CMP and a Jcc in which case the XOR will modify the condition
        // flags and interfere with the Jcc.
        masm.movlong(dst, constant);
     }

    private void const2reg(CiRegister dst, CiConstant constant) {
        assert constant.kind == CiKind.Object;
        /*
        Constant is an object therefore it is a 32 bit quantity on ARM
         */
        // Do not optimize with an XOR as this instruction may be between
        // a CMP and a Jcc in which case the XOR will modify the condition
        // flags and interfere with the Jcc.
        if (constant.isNull()) {
          //  masm.movq(dst, 0x0L);
            masm.mov32BitConstant(dst,0x0);

        } else if (target.inlineObjects) {
            tasm.recordDataReferenceInCode(constant);
            //masm.movq(dst, 0xDEADDEADDEADDEADL);
            masm.mov32BitConstant(dst,0xDEADDEAD);
        } else {
            masm.setUpScratch(tasm.recordDataReferenceInCode(constant));
            masm.addRegisters(ConditionFlag.Always,false,ARMV7.r12,ARMV7.r12,ARMV7.r15,0,0);
            masm.ldr(ConditionFlag.Always,dst, ARMV7.r12, 0);
             //masm.movq(dst, tasm.recordDataReferenceInCode(constant));
        }
    }

    @Override
    public void emitTraps() {
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 10);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 10);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 10);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 10);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 10);
        }
        // assert 0 == 1 : "emitTraps ARMV7IRAssembler";
        for (int i = 0; i < C1XOptions.MethodEndBreakpointGuards; ++i) {
             masm.int3();
        }
        masm.nop(8); // BUGFIX for overflowing buffer on patch call ...
    }

    private void const2reg(CiRegister dst, float constant) {

        // This is *not* the same as 'constant == 0.0f' in the case where constant is -0.0f
        if (Float.floatToRawIntBits(constant) == Float.floatToRawIntBits(0.0f)) {
            //masm.xorps(dst, dst);
            masm.eor(ConditionFlag.Always,false,ARMV7.r12,ARMV7.r12,ARMV7.r12,0,0);
            masm.vmov(ConditionFlag.Always,dst,ARMV7.r12);
        } else {
            masm.mov32BitConstant(rscratch1, Float.floatToRawIntBits(constant));
            masm.vmov(ConditionFlag.Always,dst,rscratch1);
            //masm.movdl(dst, rscratch1);
        }
    }

    private void const2reg(CiRegister dst, double constant) {

        // This is *not* the same as 'constant == 0.0d' in the case where constant is -0.0d
        if (Double.doubleToRawLongBits(constant) == Double.doubleToRawLongBits(0.0d)) {
            //masm.xorpd(dst, dst);
            masm.eor(ConditionFlag.Always,false,ARMV7.r8,ARMV7.r8,ARMV7.r8,0,0);
            masm.mov(ConditionFlag.Always,false,ARMV7.r9,ARMV7.r8);
            masm.vmov(ConditionFlag.Always,dst,ARMV7.r8);
        } else {
            masm.movlong(dst, Double.doubleToRawLongBits(constant));

            //masm.movq(rscratch1, Double.doubleToRawLongBits(constant));
            //masm.movdq(dst, rscratch1);
        }
    }

    @Override
    protected void const2reg(CiValue src, CiValue dest, LIRDebugInfo info) {
        assert src.isConstant();
        assert dest.isRegister();
        CiConstant c = (CiConstant) src;

        // Checkstyle: off
        switch (c.kind) {
            case Boolean :
            case Byte    :
            case Char    :
            case Short   :
            case Jsr     :
            case Int     : const2reg(dest.asRegister(), c.asInt()); break;
            case Long    : const2reg(dest.asRegister(), c.asLong()); break;
            case Object  : const2reg(dest.asRegister(), c); break;
            case Float   : const2reg(asXmmFloatReg(dest), c.asFloat()); break;
            case Double  : const2reg(asXmmDoubleReg(dest), c.asDouble()); break;
            default      : throw Util.shouldNotReachHere();
        }
        // Checkstyle: on
    }

    @Override
    protected void const2stack(CiValue src, CiValue dst) {
        assert src.isConstant();
        assert dst.isStackSlot();
        CiStackSlot slot = (CiStackSlot) dst;
        CiConstant c = (CiConstant) src;

        // Checkstyle: off
	masm.setUpScratch(frameMap.toStackAddress(slot));
        switch (c.kind) {
            case Boolean :
            case Byte    :
            case Char    :
            case Short   :
            case Jsr     :
            case Int     : //masm.movl(frameMap.toStackAddress(slot), c.asInt()); break;
                         masm.mov32BitConstant(ARMV7.r8, c.asInt());
			 masm.strImmediate(ConditionFlag.Always,0,0,0,ARMV7.r8,ARMV7.r12,0);
                          break;

            case Float   : ////masm.movl(frameMap.toStackAddress(slot), floatToRawIntBits(c.asFloat())); break;
			 masm.mov32BitConstant(ARMV7.r8, Float.floatToRawIntBits(c.asFloat()));
			 masm.strImmediate(ConditionFlag.Always,0,0,0,ARMV7.r8,ARMV7.r12,0);
			break;
            case Object  : movoop(frameMap.toStackAddress(slot), c); break;
            case Long    : //masm.movq(rscratch1, c.asLong());
			   masm.movlong(ARMV7.r8,c.asLong());
			   masm.strd(ConditionFlag.Always,ARMV7.r8,ARMV7.r12,0);
			  break;
                           //masm.movq(frameMap.toStackAddress(slot), rscratch1); break;
            case Double  : //masm.movq(rscratch1, doubleToRawLongBits(c.asDouble()));
                           masm.movlong(ARMV7.r8,Double.doubleToRawLongBits(c.asDouble()));
			   masm.strd(ConditionFlag.Always,ARMV7.r8,ARMV7.r12,0);

                           //masm.movq(frameMap.toStackAddress(slot), rscratch1); break;
                            break;
            default      : throw Util.shouldNotReachHere("Unknown constant kind for const2stack: " + c.kind);
        }
        // Checkstyle: on
    }

    @Override
    protected void const2mem(CiValue src, CiValue dst, CiKind kind, LIRDebugInfo info) {
        assert src.isConstant();
        assert dst.isAddress();
        CiConstant constant = (CiConstant) src;
        CiAddress addr = asAddress(dst);

        int nullCheckHere = codePos();

        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 11);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 11);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 11);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 11);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 11);
        }

        masm.setUpScratch(addr);
        // Checkstyle: off
        switch (kind) {
            case Boolean :
            case Byte    :// masm.movb(addr, constant.asInt() & 0xFF); break;
			masm.mov32BitConstant(ARMV7.r8, constant.asInt() & 0xFF);
                        masm.strImmediate(ConditionFlag.Always,0,0,0,ARMV7.r8,ARMV7.r12,0);

			break;

            case Char    :
            case Short   : //masm.movw(addr, constant.asInt() & 0xFFFF); break;
			masm.mov32BitConstant(ARMV7.r8,constant.asInt() & 0xFFFF);
			masm.strImmediate(ConditionFlag.Always,0,0,0,ARMV7.r8,ARMV7.r12,0);
                        break;

            case Jsr     :
            case Int     : //masm.movl(addr, constant.asInt()); break;
			masm.mov32BitConstant(ARMV7.r8,constant.asInt());
                        masm.strImmediate(ConditionFlag.Always,0,0,0,ARMV7.r8,ARMV7.r12,0);
                        break;

            case Float   : //masm.movl(addr, floatToRawIntBits(constant.asFloat())); break;
                         masm.mov32BitConstant(ARMV7.r8,Float.floatToRawIntBits(constant.asFloat()));
                         masm.strImmediate(ConditionFlag.Always,0,0,0,ARMV7.r8,ARMV7.r12,0);
			 break;

            case Object  : movoop(addr, constant); break;
            case Long    : //masm.movq(rscratch1, constant.asLong());
                         masm.movlong(ARMV7.r8,constant.asLong());

                           nullCheckHere = codePos();
                masm.strd(ConditionFlag.Always,ARMV7.r8,ARMV7.r12,0);

                           //masm.movq(addr, rscratch1); break;
                         break;
            case Double  : //masm.movq(rscratch1, doubleToRawLongBits(constant.asDouble()));
                         masm.movlong(ARMV7.r8,Double.doubleToRawLongBits(constant.asDouble()));

                           nullCheckHere = codePos();
                           //masm.movq(addr, rscratch1); break;
                masm.strd(ConditionFlag.Always,ARMV7.r8,ARMV7.r12,0);
                        break;

            default      : throw Util.shouldNotReachHere();
        }
        // Checkstyle: on

        if (info != null) {
            tasm.recordImplicitException(nullCheckHere, info);
        }
    }

    @Override
    protected void reg2reg(CiValue src, CiValue dest) {
        assert src.isRegister();
        assert dest.isRegister();

        if (dest.kind.isFloat()) {
            masm.movflt(asXmmFloatReg(dest), asXmmFloatReg(src));

        } else if (dest.kind.isDouble()) {
            masm.movdbl(asXmmDoubleReg(dest), asXmmDoubleReg(src));
        } else {
            moveRegs(src.asRegister(), dest.asRegister(), src.kind, dest.kind);
        }
    }

    @Override
    protected void reg2stack(CiValue src, CiValue dst, CiKind kind) {
        assert src.isRegister();
        assert dst.isStackSlot();
        CiAddress addr = frameMap.toStackAddress((CiStackSlot) dst);

        // Checkstyle: off
        switch (src.kind) {
            case Boolean :
            case Byte    :
            case Char    :
            case Short   :
            case Jsr     :
            case Object  :

            case Int     : //masm.movl(addr, src.asRegister());
                masm.setUpScratch(addr);
                masm.str(ConditionFlag.Always,src.asRegister(),ARMV7.r12,0);
             break;
            case Long    : //masm.movq(addr, src.asRegister());
                masm.setUpScratch(addr);
                masm.strd(ConditionFlag.Always,src.asRegister(),ARMV7.r12,0);
             break;
            case Float   : masm.movflt(addr, asXmmFloatReg(src)); break;
            case Double  : masm.movdbl(addr,asXmmDoubleReg(src));

                break;
            default      : throw Util.shouldNotReachHere();
        }
        // Checkstyle: on
    }

    @Override
    protected void reg2mem(CiValue src, CiValue dest, CiKind kind, LIRDebugInfo info, boolean unaligned) {
        CiAddress toAddr = (CiAddress) dest;


        if (info != null) {
            tasm.recordImplicitException(codePos(), info);
        }

        // Checkstyle: off
        switch (kind) {
            case Float   : masm.movflt(toAddr, asXmmFloatReg(src)); break;
            case Double  : masm.movflt(toAddr, asXmmDoubleReg(src)); break;

            case Jsr     :
            case Object  :
            case Int     : //masm.movl(toAddr, src.asRegister()); break;
                          masm.setUpScratch(toAddr); masm.strImmediate(ConditionFlag.Always,1,0,0,src.asRegister(),ARMV7.r12,0);break;
            case Long    :
                          masm.setUpScratch(toAddr);masm.strDualImmediate(ConditionFlag.Always,1,0,0,src.asRegister(),ARMV7.r12,0);
                          //masm.movq(toAddr, src.asRegister());
            break;
            case Char    :
            case Short   : masm.setUpScratch(toAddr);
                           masm.strHImmediate(ConditionFlag.Always,1,0,0,src.asRegister(),ARMV7.r12,0);
                           //masm.str, src.asRegister()); break;
                          break;
            case Byte    :
            case Boolean : //masm.movb(toAddr, src.asRegister());
                           masm.setUpScratch(toAddr);
                           masm.strbImmediate(ConditionFlag.Always, 1, 0, 0, src.asRegister(), ARMV7.r12, 0);
             break;
            default      : throw Util.shouldNotReachHere();
        }

        // Checkstyle: on
    }
    private static boolean FLOATDOUBLEREGISTERS = true;
    private static CiRegister getFloatRegister(CiRegister val) {
        int offset = 0;
        //System.out.println("LIRAgetFloatRegister val.encoding "+ val.encoding + " val.number "+ val.number);
        if(FLOATDOUBLEREGISTERS) {
            if(val.number > 31) {
                offset = 16 + val.encoding;
            } else {
                offset = 16 + 2*val.encoding;
            }
            //System.out.println(" OFFSET " + offset);
            return ARMV7.floatRegisters[offset];

        }else {
            return val;
        }
    }
    private static CiRegister asXmmFloatReg(CiValue src) {

       // System.out.println("LIRAasXmmFloatReg val.encoding "+ src.asRegister().encoding + " val.number "+ src.asRegister().number);
        assert src.kind.isFloat() : "must be float, actual kind: " + src.kind;
        //CiRegister result = src.asRegister();
        CiRegister result = getFloatRegister(src.asRegister());
        assert result.isFpu() : "must be xmm, actual type: " + result;
        return result;
    }

    @Override
    protected void stack2reg(CiValue src, CiValue dest, CiKind kind) {
        assert src.isStackSlot();
        assert dest.isRegister();

        CiAddress addr = frameMap.toStackAddress((CiStackSlot) src);
        // TODO what does movl do ... it thebase register is illegal but it
        // is the frameRegister ...
        masm.setUpScratch(addr);

       // Checkstyle: off
        switch (dest.kind) {
            case Boolean :
            case Byte    :
            case Char    :
            case Short   :
            case Jsr     :
            case Int     :
            case Object  : // Object was movq but we are 32bit address space
                masm.ldrImmediate(ConditionFlag.Always,1,0,0,dest.asRegister(),ARMV7.r12,0);
                break;
            case Long    :
                masm.ldrd(ConditionFlag.Always, dest.asRegister(), ARMV7.r12, 0);
                if (DEBUG_MOVS) {
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 40);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 40);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 40);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 40);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 40);
                }
                break;
            case Float   : masm.movflt(asXmmFloatReg(dest), addr); break;
            case Double  : masm.movdbl(asXmmDoubleReg(dest), addr); break;
            default      : throw Util.shouldNotReachHere();
        }
        // Checkstyle: on
    }

    @Override
    protected void mem2mem(CiValue src, CiValue dest, CiKind kind) {
        if (dest.kind.isInt()) {
            // we have 32 bit values ..
            masm.setUpScratch((CiAddress)src);
            masm.ldrImmediate(ConditionFlag.Always,1,0,0,ARMV7.r12,ARMV7.r12,0);
            masm.setUpRegister(ARMV7.r8,(CiAddress)dest);
            masm.strImmediate(ConditionFlag.Always,0,0,0,ARMV7.r12,ARMV7.r8,0);
            //masm.pushl((CiAddress) src);
            //masm.popl((CiAddress) dest);
	    
            // TODO WHAT ABOUT Long?
	   
        } else {
	    System.err.println("MEM2MEM PUSH POP PTR CALLED");
            masm.pushptr((CiAddress) src);
            masm.popptr((CiAddress) dest);
        }
    }

    @Override
    protected void mem2stack(CiValue src, CiValue dest, CiKind kind) {
        assert 0 == 1 : "mem2stack ARMV7IRAssembler";
	System.err.println("MEM2STACK");
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 12);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 12);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 12);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 12);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 12);
        }
        if (dest.kind.isInt()) {
            //masm.pushl((CiAddress) src);
            //masm.popl(frameMap.toStackAddress((CiStackSlot) dest));
        } else {
            masm.pushptr((CiAddress) src);
            masm.popptr(frameMap.toStackAddress((CiStackSlot) dest));
        }
    }

    @Override
    protected void stack2stack(CiValue src, CiValue dest, CiKind kind) {
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 13);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 13);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 13);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 13);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 13);
        }
	if(src.kind == CiKind.Long || src.kind == CiKind.Double )  {
            masm.setUpScratch(frameMap.toStackAddress((CiStackSlot) src));
            masm.ldrd(ConditionFlag.Always,ARMV7.r8,ARMV7.r12,0);
	    masm.setUpScratch(frameMap.toStackAddress((CiStackSlot) dest));
	    masm.strd(ConditionFlag.Always,ARMV7.r8,ARMV7.r12,0);

	} else {
            masm.setUpScratch(frameMap.toStackAddress((CiStackSlot) src));
            masm.ldrImmediate(ConditionFlag.Always,1,0,0,ARMV7.r8,ARMV7.r12,0);
	    masm.setUpScratch(frameMap.toStackAddress((CiStackSlot) dest));
	    masm.str(ConditionFlag.Always,ARMV7.r8,ARMV7.r12,0);
	}
        /*if (src.kind.isInt()) {
	   //System.out.println("stack2stack check functionality pushq popq should they load/str the values at the address prior/post push/pop");
            //masm.pushq(frameMap.toStackAddress((CiStackSlot) src));
            //masm.popq(frameMap.toStackAddress((CiStackSlot) dest));
            masm.setUpScratch(frameMap.toStackAddress((CiStackSlot) src));
            masm.ldrImmediate(ConditionFlag.Always,1,0,0,ARMV7.r8,ARMV7.r12,0);
	    masm.setUpScratch(frameMap.toStackAddress((CiStackSlot) dest));
	    masm.str(ConditionFlag.Always,ARMV7.r8,ARMV7.r12,0);
	   
        } else {
            masm.setUpScratch(frameMap.toStackAddress((CiStackSlot) src));
            masm.ldrImmediate(ConditionFlag.Always,1,0,0,ARMV7.r8,ARMV7.r12,0);
	    masm.setUpScratch(frameMap.toStackAddress((CiStackSlot) dest));
	    masm.str(ConditionFlag.Always,ARMV7.r8,ARMV7.r12,0);
            //masm.pushptr(frameMap.toStackAddress((CiStackSlot) src));
            //masm.popptr(frameMap.toStackAddress((CiStackSlot) dest));
        }
	*/
    }

    @Override
    protected void mem2reg(CiValue src, CiValue dest, CiKind kind, LIRDebugInfo info, boolean unaligned) {
        assert src.isAddress();
        assert dest.isRegister() : "dest=" + dest;


        CiAddress addr = (CiAddress) src;
        if (info != null) {
            tasm.recordImplicitException(codePos(), info);
        }

        // Checkstyle: off
        masm.setUpScratch(addr);
        switch (kind) {
            case Float   :
               // masm.movflt(asXmmFloatReg(dest), addr);
               masm.vldr(ConditionFlag.Always,asXmmFloatReg(dest),ARMV7.r12,0);
                break;
            case Double  ://
                masm.vldr(ConditionFlag.Always,asXmmDoubleReg(dest),ARMV7.r12,0);

                // masm.movdbl(asXmmDoubleReg(dest), addr);
            break;
            case Object  :// masm.movq(dest.asRegister(), addr);
            case Int:
                masm.ldrImmediate(ConditionFlag.Always,1,0,0,dest.asRegister(),ARMV7.r12,0);
                break;
            //case Int     : masm.movslq(dest.asRegister(), addr); break;
            case Long    : //masm.movq(dest.asRegister(), addr);
                    masm.ldrd(ConditionFlag.Always, dest.asRegister(), ARMV7.r12, 0);
                if (DEBUG_MOVS) {
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 41);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 41);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 41);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 41);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 41);
                }

                break;
            case Boolean :
            case Byte    :// masm.movsxb(dest.asRegister(), addr);
                    masm.ldrsb(ConditionFlag.Always,1,0,0,dest.asRegister(),ARMV7.r12,0);
                break;
            case Char    : //masm.movzxl(dest.asRegister(), addr);
                masm.ldrb(ConditionFlag.Always,1,0,0,dest.asRegister(),ARMV7.r12,0);
                break;
            case Short   : //masm.movswl(dest.asRegister(), addr);
                masm.ldrshw(ConditionFlag.Always,1,0,0,dest.asRegister(),ARMV7.r12,0);
                break;
            default      : throw Util.shouldNotReachHere();
        }
        // Checkstyle: on
    }

    @Override
    protected void emitReadPrefetch(CiValue src) {
        assert 0 == 1 : "emitReadPrefetch ARMV7IRAssembler";
        CiAddress addr = (CiAddress) src;
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 14);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 14);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 14);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 14);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 14);
        }
        // Checkstyle: off
        switch (C1XOptions.ReadPrefetchInstr) {
            case 0  :// masm.prefetchnta(addr);
                break;
            case 1  : //masm.prefetcht0(addr);
                break;
            case 2  : //masm.prefetcht2(addr);
                break;
            default : throw Util.shouldNotReachHere();
        }
        // Checkstyle: on
    }

    @Override
    protected void emitOp3(LIROp3 op) {
        // Checkstyle: off
        switch (op.code) {
            case Idiv  :
            case Irem  : arithmeticIdiv(op.code, op.opr1(), op.opr2(), op.result(), op.info); break;
            case Iudiv :
            case Iurem : arithmeticIudiv(op.code, op.opr1(), op.opr2(), op.result(), op.info); break;
            case Ldiv  :
            case Lrem  : arithmeticLdiv(op.code, op.opr1(), op.opr2(), op.result(), op.info); break;
            case Ludiv:
            case Lurem:
                 arithmeticLudiv(op.code, op.opr1(), op.opr2(), op.result(), op.info); break;
            default    : throw Util.shouldNotReachHere();
        }
        // Checkstyle: on
    }

    private boolean assertEmitBranch(LIRBranch op) {
        assert op.block() == null || op.block().label() == op.label() : "wrong label";
        if (op.block() != null) {
            branchTargetBlocks.add(op.block());
        }
        if (op.unorderedBlock() != null) {
            branchTargetBlocks.add(op.unorderedBlock());
        }
        return true;
    }

    private boolean assertEmitTableSwitch(LIRTableSwitch op) {
        assert op.defaultTarget != null;
        branchTargetBlocks.add(op.defaultTarget);
        for (BlockBegin target : op.targets) {
            assert target != null;
            branchTargetBlocks.add(target);
        }
        return true;
    }

    @Override
    protected void emitTableSwitch(LIRTableSwitch op) {
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 15);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 15);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 15);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 15);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 15);
        }
        assert assertEmitTableSwitch(op);
        //assert 0 == 1 : "emitTableSwitch ARMV7IRAssembler";
        //Log.println("C1X ARMV7LIRAssembler tableswitch debug");
        CiRegister value = op.value().asRegister();
        final Buffer buf = masm.codeBuffer;

        // Compare index against jump table bounds
        int highKey = op.lowKey + op.targets.length - 1;
        if (op.lowKey != 0) {
            // subtract the low value from the switch value
           masm.subq(value, op.lowKey); // was subl ... am assuming that the register is NOT storing a long
            masm.cmpl(value, highKey - op.lowKey);
        } else {
            masm.cmpl(value, highKey);
        }

        // Jump to default target if index is not within the jump table
       //masm.jcc(ConditionFlag.above, op.defaultTarget.label());
        masm.jcc(ConditionFlag.SignedGreater,op.defaultTarget.label());
        masm.cmpl(value,op.lowKey); // added
        masm.jcc(ConditionFlag.SignedLesser,op.defaultTarget.label());

        // Set scratch to address of jump table
        int leaPos = buf.position();
        masm.leaq(rscratch1, CiAddress.Placeholder);
        int afterLea = buf.position();

        // Load jump table entry into scratch and jump to it
        //masm.movslq(value, new CiAddress(CiKind.Int, rscratch1.asValue(), value.asValue(), Scale.Times4, 0));
        masm.setUpRegister(value, new CiAddress(CiKind.Int, rscratch1.asValue(), value.asValue(), CiAddress.Scale.Times4, 0));
        //masm.ldr(ConditionFlag.Always,value,value,0);
        // TODO do we need to load the value stored at the address done above but might be wrong
       //masm.addq(rscratch1, value);
        masm.add(ConditionFlag.Always,false,rscratch1,value,0,0);
        //masm.ldr(ConditionFlag.Always,rscratch1,rscratch1,0);
        masm.jmp(rscratch1);

        // Inserting padding so that jump table address is 4-byte aligned
        if ((buf.position() & 0x3) != 0) {
            masm.nop(4 - (buf.position() & 0x3));
        }

        // Patch LEA instruction above now that we know the position of the jump table
        int jumpTablePos = buf.position();
        buf.setPosition(leaPos);
        masm.leaq(rscratch1, new CiAddress(target.wordKind, ARMV7.r15.asValue(), jumpTablePos - afterLea)); // rip now r15
        buf.setPosition(jumpTablePos);

        // Emit jump table entries
        for (BlockBegin target : op.targets) {
            Label label = target.label();
            int offsetToJumpTableBase = buf.position() - jumpTablePos;
            if (label.isBound()) {
                int imm32 = label.position() - jumpTablePos;
                buf.emitInt(imm32);
            } else {
                label.addPatchAt(buf.position());
                buf.emitInt((ConditionFlag.NeverUse.value() << 28)|(offsetToJumpTableBase<< 12)|0x0d0);
                //Log.println("PATCH at " + buf.position() + " " + offsetToJumpTableBase + " " +Integer.toHexString(offsetToJumpTableBase));
                //buf.emitByte(0); // psuedo-opcode for jump table entry
                //buf.emitShort(offsetToJumpTableBase);
                //buf.emitByte(0); // padding to make jump table entry 4 bytes wide
            }
        }

        JumpTable jt = new JumpTable(jumpTablePos, op.lowKey, highKey, 4);
        tasm.targetMethod.addAnnotation(jt);

       // Log.println("tableswitch emitted");
    }

    @Override
    protected void emitBranch(LIRBranch op) {

        assert assertEmitBranch(op);
        //assert 0 == 1 : "emitBranch ARMV7IRAssembler";

        if (op.cond() == Condition.TRUE) {
            if (op.info != null) {
                tasm.recordImplicitException(codePos(), op.info);
            }
           masm.jmp(op.label());
        } else {
            ConditionFlag acond = ConditionFlag.Always;
            if (op.code == LIROpcode.CondFloatBranch) {
                assert op.unorderedBlock() != null : "must have unordered successor";
                //masm.jcc(ConditionFlag.parity, op.unorderedBlock().label());
                masm.jcc(ConditionFlag.SignedOverflow,op.unorderedBlock().label());
                /* http://community.arm.com/groups/processors/blog/2013/09/25/condition-codes-4-floating-point-comparisons-using-vfp
                See link,  table explains the ARM way of determining if one or more arguments was a NaN
                floating-point logical relationships after a compare must use integer codes, that do not map one-to-one
                SignedOverflow means one or more arguments were NaN
                 */
                // parityset in X86 test for FP compares means that a NaN has occurred
                // TODO how to encode the parity condition bit for X86??? masm.jcc(ConditionFlag., op.unorderedBlock().label());
                //assert 0 == 1 : "parity flag ARMV7IRAssembler";

                // Checkstyle: off
                switch (op.cond()) {
                    case EQ : //acond = ConditionFlag.equal;
                        acond = ConditionFlag.Equal;
                        break;
                    case NE :
                        acond = ConditionFlag.NotEqual;
                        //acond = ConditionFlag.notEqual;
                        break;
                    case LT :
                        acond = ConditionFlag.CarryClearUnsignedLower;
                        //acond = ConditionFlag.below;
                        break;
                    case LE :
                        acond = ConditionFlag.UnsignedLowerOrEqual;
                        //acond = ConditionFlag.belowEqual;
                        break;
                    case GE :
                        acond = ConditionFlag.SignedGreaterOrEqual;
                        //acond = ConditionFlag.aboveEqual;
                        break;
                    case GT :
                        acond = ConditionFlag.SignedGreater;
                        //acond = ConditionFlag.above;
                        break;
                    default : throw Util.shouldNotReachHere();
                }
            } else {
                switch (op.cond()) {
                    case EQ : acond = ConditionFlag.Equal; /*ConditionFlag.equal;*/ break;
                    case NE : acond = ConditionFlag.NotEqual; /*notEqual;*/ break;
                    case LT : acond = ConditionFlag.SignedLesser; /*less;*/ break;
                    case LE : acond = ConditionFlag.SignedLowerOrEqual; /*lessEqual;*/ break;
                    case GE : acond = ConditionFlag.SignedGreaterOrEqual; /*greaterEqual;*/ break;
                    case GT : acond = ConditionFlag.SignedGreater; /*greater;*/ break;
                    case BE : acond = ConditionFlag.UnsignedLowerOrEqual; /*belowEqual;*/ break;
                    case AE : acond = ConditionFlag.CarrySetUnsignedHigherEqual; /*aboveEqual;*/ break;
                    case BT : acond = ConditionFlag.CarryClearUnsignedLower; /*below;*/ break;
                    case AT : acond = ConditionFlag.UnsignedHigher; /*above;*/ break;
                    default : throw Util.shouldNotReachHere();
                }
                // Checkstyle: on
            }
            masm.jcc(acond, op.label());
        }
    }

    @Override
    protected void emitConvert(LIRConvert op) {
        CiValue src = op.operand();
        CiValue dest = op.result();
        Label endLabel = new Label();

        CiRegister srcRegister = src.asRegister();
        switch (op.opcode) {
            case I2L:
                moveRegs(srcRegister, dest.asRegister(), src.kind, dest.kind);
                break;
            case L2I:
                moveRegs(srcRegister, dest.asRegister(), src.kind, dest.kind);
                break;
            case I2B:
                moveRegs(srcRegister, dest.asRegister());
                //masm.signExtendByte(dest.asRegister() );
                masm.sxtb(ConditionFlag.Always,dest.asRegister(),srcRegister);
                break;

            case I2C:
                masm.mov32BitConstant(dest.asRegister(), 0xFFFF);
                masm.and(ConditionFlag.Always, true, dest.asRegister(), srcRegister, dest.asRegister(), 0, 0);
                break;
            case I2S:
                //masm.signExtendShort(dest.asRegister(),srcRegister);
                moveRegs(srcRegister, dest.asRegister());
                masm.sxth(ConditionFlag.Always,dest.asRegister(),srcRegister);
                break;
            case F2D:
                masm.vcvt(ConditionFlag.Always,asXmmDoubleReg(dest),false,false,asXmmFloatReg(src));
                //masm.cvtss2sd(asXmmDoubleReg(dest), asXmmFloatReg(src));
                break;

            case D2F:
                masm.vcvt(ConditionFlag.Always,asXmmFloatReg(dest),false,false,asXmmDoubleReg(src));
               // masm.cvtsd2ss(asXmmFloatReg(dest), asXmmDoubleReg(src));
                break;

            case I2F:
                masm.vmov(ConditionFlag.Always,ARMV7.s30,srcRegister);
                masm.vcvt(ConditionFlag.Always,asXmmFloatReg(dest),false,true,ARMV7.s30);
                //masm.cvtsi2ssl(asXmmFloatReg(dest), srcRegister);
                break;
            case I2D:
                /* vcvt only works on FP regs so need to do a vmov first to FP scratch */
                masm.vmov(ConditionFlag.Always,ARMV7.s30,srcRegister);
                masm.vcvt(ConditionFlag.Always,asXmmDoubleReg(dest),false,true,ARMV7.s30);

                // masm.cvtsi2sdl(asXmmDoubleReg(dest), srcRegister);
                break;

            case F2I: {
                assert srcRegister.isFpu() && dest.isRegister() : "must both be XMM register (no fpu stack)";
                //assert 0 == 1 : " F2I ARMV7LIRAssembler bind commented out";
                //System.out.println("F2I: ARMVLIRAssembler over simplification? replaced with vcvt");
                masm.vcvt(ConditionFlag.Always,ARMV7.s30,true,true,asXmmFloatReg(src));
                masm.vmov(ConditionFlag.Always,dest.asRegister(),ARMV7.s30);

                // masm.cvttss2sil(dest.asRegister(), srcRegister);
               /* masm.cmp32(dest.asRegister(), Integer.MIN_VALUE);
                masm.jcc(ConditionFlag.NotEqual, endLabel);
                callStub(op.stub, null, dest.asRegister(), src);
                // cannot cause an exception
                masm.bind(endLabel);
                */
                break;
            }
            case D2I: {
                assert srcRegister.isFpu() && dest.isRegister() : "must both be XMM register (no fpu stack)";
                //masm.cvttsd2sil(dest.asRegister(), asXmmDoubleReg(src));

                //masm.vcvt(ConditionFlag.Always,dest.asRegister(),true,true,asXmmDoubleReg(src));
                /*
                VCVT can convert to from only in the SP DP regs so we must use the SP/DP scratch and then
                vmov to the core registers!
                 */
                if(dest.asRegister().isFpu()) {
                    masm.vcvt(ConditionFlag.Always,asXmmFloatReg(dest),true,true,asXmmDoubleReg(src));
                }else {
                    masm.vcvt(ConditionFlag.Always,ARMV7.s30,true,true,asXmmDoubleReg(src));
                    masm.vmov(ConditionFlag.Always,dest.asRegister(),ARMV7.s30);

                }
                //Log.println("ARMV7LIRAssembler D2I hack replaced stub with vcvt for quick test");
                /*masm.cmp32(dest.asRegister(), Integer.MIN_VALUE);
                masm.jcc(ConditionFlag.NotEqual, endLabel);
                callStub(op.stub, null, dest.asRegister(), src);
                // cannot cause an exception
                masm.bind(endLabel);*/
                break;
            }
            case L2F:
                if (DEBUG_MOVS) {
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 16);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 16);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 16);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 16);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 16);
                }

                //assert 0 == 1: "long to float convert";
               // System.out.println("MISSING: long to float convert no timplemented");
                //masm.cvtsi2ssq(asXmmFloatReg(dest), srcRegister);
                //masm.cvtsi2ssq(asXmmFloatReg(dest), srcRegister);
                break;

            case L2D:
                //assert 0 == 1: "long to double convert";
                //System.out.println("MISSING: long to double conver not implemented");
                if (DEBUG_MOVS) {
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 17);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 17);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 17);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 17);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 17);
                }

                //masm.cvtsi2sdq(asXmmDoubleReg(dest), srcRegister);
                break;

            case F2L: {
                if (DEBUG_MOVS) {
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 18);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 18);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 18);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 18);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 18);
                }

                //assert (0 == 1) : " float to long convert";
		//System.out.println("MISSING: F2L convert not iplemented");
                assert srcRegister.isFpu() && dest.kind.isLong() : "must both be XMM register (no fpu stack)";
                //masm.cvttss2siq(dest.asRegister(), asXmmFloatReg(src));
                //masm.movq(rscratch1, java.lang.Long.MIN_VALUE);
                //masm.cmpq(dest.asRegister(), rscratch1);
                masm.jcc(ConditionFlag.NotEqual, endLabel);
                callStub(op.stub, null, dest.asRegister(), src);
                masm.bind(endLabel);
                break;
            }

            case D2L: {
                if (DEBUG_MOVS) {
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 19);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 19);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 19);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 19);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 19);
                }
                //assert (0 == 1) : " double to long convert";
		//System.out.println("MISSING: D2L notimplemented as a conversion needs runtime routines from fplib");
                assert srcRegister.isFpu() && dest.kind.isLong() : "must both be XMM register (no fpu stack)";
                //masm.cvttsd2siq(dest.asRegister(), asXmmDoubleReg(src));
                //masm.movq(rscratch1, java.lang.Long.MIN_VALUE);
                //masm.cmpq(dest.asRegister(), rscratch1);
                masm.jcc(ConditionFlag.NotEqual, endLabel);
                callStub(op.stub, null, dest.asRegister(), src);
                masm.bind(endLabel);
                break;
            }

            case MOV_I2F:
                masm.vcvt(ConditionFlag.Always,asXmmFloatReg(dest),false,true,srcRegister);
               // masm.movdl(asXmmFloatReg(dest), srcRegister);
                break;

            case MOV_L2D:
                if (DEBUG_MOVS) {
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 20);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 20);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 20);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 20);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 20);
                }
               // System.out.println("MISSING: ARMV7LIRASssembler MOV_L2D");
                //assert (0 == 1) : " long to double --- convert";
               // masm.movdq(asXmmDoubleReg(dest), srcRegister);
                break;

            case MOV_F2I:
                masm.vcvt(ConditionFlag.Always,dest.asRegister(),true,true,asXmmFloatReg(src));

                //masm.movdl(dest.asRegister(), asXmmFloatReg(src));
                break;

            case MOV_D2L:
                if (DEBUG_MOVS) {
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 21);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 21);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 21);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 21);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 21);
                }
                //assert (0 == 1): " double to long mov compare";
                //System.out.println("MISSING: double to long mov compare not implemented");
                //masm.movdq(dest.asRegister(), asXmmDoubleReg(src));
                break;

            default:
                throw Util.shouldNotReachHere();
        }
    }

    @Override
    protected void emitCompareAndSwap(LIRCompareAndSwap op) {
        CiAddress address = new CiAddress(CiKind.Object, op.address(), 0);
        CiRegister newval = op.newValue().asRegister();
        CiRegister cmpval = op.expectedValue().asRegister();
        // assert cmpval == ARMV7.rax : "wrong register";
        assert newval != null : "new val must be register";
        assert cmpval != newval : "cmp and new values must be in different registers";
        assert cmpval != address.base() : "cmp and addr must be in different registers";
        assert newval != address.base() : "new value and addr must be in different registers";
        assert cmpval != address.index() : "cmp and addr must be in different registers";
        assert newval != address.index() : "new value and addr must be in different registers";
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 22);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 22);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 22);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 22);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 22);
        }

        if (compilation.target.isMP) {
            masm.membar(-1);
        }
        if (op.code == LIROpcode.CasInt || op.code == LIROpcode.CasObj) {
            //((CiRegisterConfig)masm.registerConfig).printAllocatable();
            masm.casInt(newval, cmpval, address);
        } else {
            //System.out.println("AM I EVER CALLED");
            assert op.code == LIROpcode.CasLong;
            masm.casLong(newval, cmpval, address);
        }
    }

    @Override
    protected void emitConditionalMove(Condition condition, CiValue opr1, CiValue opr2, CiValue result) {
        ConditionFlag acond;
        ConditionFlag ncond;
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 23);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 23);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 23);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 23);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 23);
        }

        switch (condition) {
            case EQ:
                acond = ConditionFlag.Equal;
                ncond = ConditionFlag.NotEqual;
                //ncond = ConditionFlag.notEqual;
                break;
            case NE:
                ncond = ConditionFlag.Equal;
                acond = ConditionFlag.NotEqual;
               // acond = ConditionFlag.notEqual;
               // ncond = ConditionFlag.equal;
                break;
            case LT:
                acond = ConditionFlag.SignedLesser;
                ncond = ConditionFlag.SignedGreaterOrEqual;
               // acond = ConditionFlag.less;
               // ncond = ConditionFlag.greaterEqual;
                break;
            case LE:
                acond = ConditionFlag.SignedLowerOrEqual;
                ncond = ConditionFlag.SignedGreater;
               // acond = ConditionFlag.lessEqual;
               // ncond = ConditionFlag.greater;
                break;
            case GE:
                acond = ConditionFlag.SignedGreaterOrEqual;
                ncond = ConditionFlag.SignedLesser;
               // acond = ConditionFlag.greaterEqual;
               // ncond = ConditionFlag.less;
                break;
            case GT:
                acond = ConditionFlag.SignedGreater;
                ncond = ConditionFlag.SignedLowerOrEqual;
               // acond = ConditionFlag.greater;
              //  ncond = ConditionFlag.lessEqual;
                break;
            case BE:
                acond  = ConditionFlag.UnsignedLowerOrEqual;
                ncond = ConditionFlag.UnsignedHigher;
               // acond = ConditionFlag.belowEqual;
               // ncond = ConditionFlag.above;
                break;
            case BT:
                acond = ConditionFlag.CarryClearUnsignedLower;
                ncond = ConditionFlag.CarrySetUnsignedHigherEqual;
               // acond = ConditionFlag.below;
               // ncond = ConditionFlag.aboveEqual;
                break;
            case AE:
                acond = ConditionFlag.CarrySetUnsignedHigherEqual;
                ncond = ConditionFlag.CarryClearUnsignedLower;
               // acond = ConditionFlag.aboveEqual;
              //  ncond = ConditionFlag.below;
                break;
            case AT:
                acond = ConditionFlag.UnsignedHigher;
                ncond = ConditionFlag.UnsignedLowerOrEqual;
               // acond = ConditionFlag.above;
               // ncond = ConditionFlag.belowEqual;
                break;
            default:
                throw Util.shouldNotReachHere();
        }

        CiValue def = opr1; // assume left operand as default
        CiValue other = opr2;

        if (opr2.isRegister() && opr2.asRegister() == result.asRegister()) {
            // if the right operand is already in the result register, then use it as the default
            def = opr2;
            other = opr1;
            // and flip the condition
            acond = ConditionFlag.Always;
            ncond = ConditionFlag.Always;
            ConditionFlag tcond = acond;
            acond = ncond;
            ncond = tcond;
        }

        if (def.isRegister()) {
            reg2reg(def, result);
        } else if (def.isStackSlot()) {
            stack2reg(def, result, result.kind);
        } else {
            assert def.isConstant();
            const2reg(def, result, null);
        }

        //assert 0 == 1 : "emitConditionalMove ARMV7IRAssembler";

        if (!other.isConstant()) {
            // optimized version that does not require a branch
            if (other.isRegister()) {
                assert other.asRegister() != result.asRegister() : "other already overwritten by previous move";
                if (other.kind.isInt()) {
			masm.mov(ncond,false,result.asRegister(), other.asRegister());
                   // masm.cmovq(ncond, result.asRegister(), other.asRegister());
                } else {
			masm.mov(ncond,false,result.asRegister(), other.asRegister());
                   // masm.cmovq(ncond, result.asRegister(), other.asRegister());
                }
            } else {
                assert other.isStackSlot();
                CiStackSlot otherSlot = (CiStackSlot) other;
		masm.setUpScratch(frameMap.toStackAddress(otherSlot));
                masm.ldrImmediate(ConditionFlag.Always,1,0,0,ARMV7.r12,ARMV7.r12,0);
                if (other.kind.isInt()) {
			masm.mov(ncond,false, result.asRegister(),ARMV7.r12);
                   // masm.cmovl(ncond, result.asRegister(), frameMap.toStackAddress(otherSlot));
                } else {
                   // masm.cmovq(ncond, result.asRegister(), frameMap.toStackAddress(otherSlot));
			masm.mov(ncond,false, result.asRegister(),ARMV7.r12);
                }
            }

        } else {
            // conditional move not available, use emit a branch and move
            Label skip = new Label();
            masm.jcc(acond, skip);
            if (other.isRegister()) {
                reg2reg(other, result);
            } else if (other.isStackSlot()) {
                stack2reg(other, result, result.kind);
            } else {
                assert other.isConstant();
                const2reg(other, result, null);
            }
            masm.bind(skip);
        }
    }

    @Override
    protected void emitArithOp(LIROpcode code, CiValue left, CiValue right, CiValue dest, LIRDebugInfo info) {
        assert info == null : "should never be used :  idiv/irem and ldiv/lrem not handled by this method";
        assert Util.archKindsEqual(left.kind, right.kind) || (left.kind == CiKind.Long && right.kind == CiKind.Int) : code.toString() + " left arch is " + left.kind + " and right arch is " +
                        right.kind;
        assert left.equals(dest) : "left and dest must be equal";
        CiKind kind = left.kind;

        // Checkstyle: off
        if (left.isRegister()) {
            CiRegister lreg = left.asRegister();

            if (right.isRegister()) {
                // register - register
                CiRegister rreg = right.asRegister();
                if (kind.isInt()) {
                    switch (code) {
                        case Add:
                            masm.iadd(dest.asRegister(), lreg, rreg);
                            break;
                        case Sub:
                            masm.isub(dest.asRegister(), lreg, rreg);
                            break;
                        case Mul:
                            masm.imul(dest.asRegister(), lreg, rreg);
                            break;
                        default:
                            throw Util.shouldNotReachHere();
                    }
                } else if (kind.isFloat()) {
                    assert rreg.isFpu() : "must be xmm";
                    switch (code) {
                        case Add: // masm.addss(lreg, rreg);
                            masm.vadd(ConditionFlag.Always, asXmmFloatReg(dest), asXmmFloatReg(left), asXmmFloatReg(right));
                            break;
                        case Sub: // masm.subss(lreg, rreg);
                            masm.vsub(ConditionFlag.Always, asXmmFloatReg(dest), asXmmFloatReg(left), asXmmFloatReg(right));
                            break;
                        case Mul: // masm.mulss(lreg, rreg);
                            masm.vmul(ConditionFlag.Always, asXmmFloatReg(dest), asXmmFloatReg(left), asXmmFloatReg(right));
                            break;
                        case Div: // masm.divss(lreg, rreg);
                            // masm.vdiv(ConditionFlag.Always,lreg,lreg,rreg);
                            masm.vdiv(ConditionFlag.Always, asXmmFloatReg(dest), asXmmFloatReg(left), asXmmFloatReg(right));
                            break;
                        default:
                            throw Util.shouldNotReachHere();
                    }
                } else if (kind.isDouble()) {
                    assert rreg.isFpu();
                    switch (code) {
                        case Add: // masm.addsd(lreg, rreg);
                            masm.vadd(ConditionFlag.Always, lreg, lreg, rreg);
                            break;
                        case Sub: // masm.subsd(lreg, rreg);
                            masm.vsub(ConditionFlag.Always, lreg, lreg, rreg);
                            break;
                        case Mul: // masm.mulsd(lreg, rreg);
                            masm.vmul(ConditionFlag.Always, lreg, lreg, rreg);
                            break;
                        case Div: // masm.divsd(lreg, rreg);
                            masm.vdiv(ConditionFlag.Always, lreg, lreg, rreg);
                            break;
                        default:
                            throw Util.shouldNotReachHere();
                    }
                } else {
                    assert kind.isLong();
                    switch (code) {
                        case Add: masm.addLong(dest.asRegister(), lreg, rreg);
                            break;
                        case Sub: masm.subLong(dest.asRegister(), lreg, rreg);
                            break;
                        case Mul:  masm.mulLong(dest.asRegister(), lreg, rreg);
                            break;
                        default:
                            throw Util.shouldNotReachHere();
                    }
                }
            } else {
                if (kind.isInt()) {
                    if (right.isStackSlot()) {
                        // register - stack
                        CiAddress raddr = frameMap.toStackAddress(((CiStackSlot) right));
                        switch (code) {
                            case Add:
                                masm.iadd(dest.asRegister(), lreg, raddr);
                                break;
                            case Sub:
                                masm.isub(dest.asRegister(), lreg, raddr);
                                break;
                            default:
                                throw Util.shouldNotReachHere();
                        }
                    } else if (right.isConstant()) {
                        // register - constant
                        assert kind.isInt();
                        int delta = ((CiConstant) right).asInt();
                        switch (code) {
                            case Add:
                                masm.incrementl(lreg, delta);
                                break;
                            case Sub:
                                masm.decrementl(lreg, delta);
                                break;
                            default:
                                throw Util.shouldNotReachHere();
                        }
                    }
                } else if (kind.isFloat()) {
                    // register - stack/constant
                    CiAddress raddr;
                    if (right.isStackSlot()) {
                        raddr = frameMap.toStackAddress(((CiStackSlot) right));
                        masm.setUpScratch(raddr);

                    } else {
                        assert right.isConstant();
                        raddr = tasm.recordDataReferenceInCode(CiConstant.forFloat(((CiConstant) right).asFloat()));
                        masm.setUpScratch(raddr);
                        masm.addRegisters(ConditionFlag.Always,false,ARMV7.r12,ARMV7.r12,ARMV7.r15,0,0);
                    }
                    masm.vldr(ConditionFlag.Always,ARMV7.s30,ARMV7.r12,0);
                    switch (code) {
                        case Add: // masm.addss(lreg, raddr)
                            masm.vadd(ConditionFlag.Always,getFloatRegister(lreg),getFloatRegister(lreg),ARMV7.s30);

                            break;
                        case Sub: // masm.subss(lreg, raddr);
                            masm.vsub(ConditionFlag.Always,getFloatRegister(lreg),getFloatRegister(lreg),ARMV7.s30);
                            break;
                        case Mul: // masm.mulss(lreg, raddr);
                            masm.vmul(ConditionFlag.Always,getFloatRegister(lreg),getFloatRegister(lreg),ARMV7.s30);
                            break;
                        case Div: // masm.divss(lreg, raddr);
                            masm.vdiv(ConditionFlag.Always,getFloatRegister(lreg),getFloatRegister(lreg),ARMV7.s30);
                            break;
                        default:
                            throw Util.shouldNotReachHere();
                    }
                } else if (kind.isDouble()) {
                    // register - stack/constant
                    CiAddress raddr;
                    if (right.isStackSlot()) {
                        raddr = frameMap.toStackAddress(((CiStackSlot) right));
                        masm.setUpScratch(raddr);
                    } else {
                        assert right.isConstant();
                        raddr = tasm.recordDataReferenceInCode(CiConstant.forDouble(((CiConstant) right).asDouble()));
                        masm.setUpScratch(raddr);
                        masm.addRegisters(ConditionFlag.Always,false,ARMV7.r12,ARMV7.r12,ARMV7.r15,0,0);
                    }

                    //System.out.println("DUBIOUS: double const arithmetic");
                    //masm.setUpScratch(raddr);
                    masm.vldr(ConditionFlag.Always,ARMV7.d15,ARMV7.r12,0);
                    switch (code) {
                        case Add: // masm.addsd(lreg, raddr);
                            masm.vadd(ConditionFlag.Always,lreg,lreg,ARMV7.d15);
                            break;
                        case Sub: // masm.subsd(lreg, raddr);
                            masm.vsub(ConditionFlag.Always,lreg,lreg,ARMV7.d15);

                            break;
                        case Mul: // masm.mulsd(lreg, raddr);
                            masm.vmul(ConditionFlag.Always,lreg,lreg,ARMV7.d15);

                            break;
                        case Div:// masm.divsd(lreg, raddr);
                            masm.vdiv(ConditionFlag.Always,lreg,lreg,ARMV7.d15);

                            break;
                        default:
                            throw Util.shouldNotReachHere();
                    }
                } else {
                    assert target.sizeInBytes(kind) == 8;
                    if (right.isStackSlot()) {
                        // register - stack
                        CiAddress raddr = frameMap.toStackAddress(((CiStackSlot) right));
                        masm.setUpScratch(raddr);
                        masm.ldrImmediate(ConditionFlag.Always, 1, 0, 0, ARMV7.r12, ARMV7.r12, 0);
                        // TODO what if addq subq so might be longs
                        switch (code) {
                            case Add: // masm.addq(ConditionFlag.Always,false,lreg,ARMV7.r12,0,0);
                                masm.addRegisters(ConditionFlag.Always, false, lreg,lreg, ARMV7.r12, 0, 0);
                                break;
                            case Sub:
                                masm.sub(ConditionFlag.Always, false, lreg,lreg, ARMV7.r12, 0, 0);
                                // masm.subq(ConditionFlag.Always,false,lreg, ARMV7.r12,0,0);
                                break;
                            default:
                                throw Util.shouldNotReachHere();
                        }
                    } else {
                        // register - constant
                        assert right.isConstant();
                        long c = ((CiConstant) right).asLong();
                        if (DEBUG_MOVS) {
                            masm.movw(ConditionFlag.Always, ARMV7.r12, 44);
                            masm.movw(ConditionFlag.Always, ARMV7.r12, 44);
                            masm.movw(ConditionFlag.Always, ARMV7.r12, 44);
                            masm.movw(ConditionFlag.Always, ARMV7.r12, 44);
                            masm.movw(ConditionFlag.Always, ARMV7.r12, 44);
                        }
                        /*if (NumUtil.isInt(c)) {
                            switch (code) {
                                case Add:
                                    masm.addq(lreg, (int) c);
                                    break;
                                case Sub:
                                    masm.subq(lreg, (int) c);
                                    break;
                                default:
                                    throw Util.shouldNotReachHere();
                            }
                        } else */{
                            masm.movlong(ARMV7.r8,c);
                            // masm.movq(rscratch1, c);
                            // masm.mov32BitConstant();
                            switch (code) {
                                case Add: masm.addLong(lreg,lreg,ARMV7.r8);
                                    break;
                                case Sub: // masm.subq(lreg, rscratch1);
                                    masm.subLong(lreg,lreg,ARMV7.r8);
                                    break;
                                default:
                                    throw Util.shouldNotReachHere();
                            }
                        }
                    }
                }
            }
        } else {
            assert kind.isInt();
            CiAddress laddr = asAddress(left);

            if (right.isRegister()) {
                CiRegister rreg = right.asRegister();
                masm.setUpScratch(laddr);
                masm.ldr(ConditionFlag.Always, ARMV7.r8, ARMV7.r12, 0);
                switch (code) {
                    case Add: // masm.addl(laddr, rreg);
                        masm.addRegisters(ConditionFlag.Always, false, ARMV7.r8, ARMV7.r8, rreg, 0, 0);
                        break;
                    case Sub: // masm.subl(laddr, rreg);
                        masm.sub(ConditionFlag.Always, false, ARMV7.r8, ARMV7.r8, rreg, 0, 0);
                        break;
                    default:
                        throw Util.shouldNotReachHere();

                }
                masm.str(ConditionFlag.Always, ARMV7.r8, ARMV7.r12, 0);
            } else {
                assert right.isConstant();
                int c = ((CiConstant) right).asInt();
                switch (code) {
                    case Add:
                        masm.incrementl(laddr, c);
                        break;
                    case Sub:
                        masm.decrementl(laddr, c);
                        break;
                    default:
                        throw Util.shouldNotReachHere();
                }
                if (DEBUG_MOVS) {
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 43);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 43);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 43);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 43);
                    masm.movw(ConditionFlag.Always, ARMV7.r12, 43);
                }
            }
        }
        // Checkstyle: on
    }

    @Override
    protected void emitIntrinsicOp(LIROpcode code, CiValue value, CiValue unused, CiValue dest, LIROp2 op) {
        assert value.kind.isDouble();

        switch (code) {
            case Abs:
                if (asXmmDoubleReg(dest) != asXmmDoubleReg(value)) {
                    masm.movdbl(asXmmDoubleReg(dest), asXmmDoubleReg(value));
                }
                int op1,op2;
                op1 = asXmmDoubleReg(dest).encoding;
                op2 = asXmmDoubleReg(value).encoding;
                // crude ..
                masm.vmov(ConditionFlag.Always,ARMV7.r8,ARMV7.floatRegisters[op1*2]);
                masm.vmov(ConditionFlag.Always,ARMV7.r9,ARMV7.floatRegisters[op2*2]);
                masm.and(ConditionFlag.Always,false,ARMV7.r8,ARMV7.r9,ARMV7.r8,0,0);
                masm.vmov(ConditionFlag.Always,ARMV7.floatRegisters[op1*2],ARMV7.r8);
                masm.vmov(ConditionFlag.Always,ARMV7.r8,ARMV7.floatRegisters[op1*2+1]);
                masm.vmov(ConditionFlag.Always,ARMV7.r9,ARMV7.floatRegisters[op2*2+1]);
                masm.and(ConditionFlag.Always,false,ARMV7.r8,ARMV7.r9,ARMV7.r8,0,0);
                masm.vmov(ConditionFlag.Always,ARMV7.floatRegisters[op1*2+1],ARMV7.r8);
                //System.out.println("MISSING emitIntrinsicOp masm.andpd ");
		/*
			this implementes ai 66 0F 54 /r	ANDPD xmm1, xmm2/m128	Bitwise logical AND of xmm2/m128 and xmm1. If any part of the operand lies outside the effective address space from 0 to FFFFH. bitwise logical
			the strategy is to move the value a float at at a time into r8 r9 and then to do the and then to move back into the dest (float)?
		*/
          //      masm.andpd(asXmmDoubleReg(dest), tasm.recordDataReferenceInCode(CiConstant.forLong(DoubleSignMask), 16));

                break;

            case Sqrt:
                masm.vsqrt(ConditionFlag.Always,asXmmDoubleReg(dest), asXmmDoubleReg(value));
                break;

            default:
                throw Util.shouldNotReachHere();
        }
    }

    @Override
    protected void emitLogicOp(LIROpcode code, CiValue left, CiValue right, CiValue dst) {
        assert left.isRegister();

	assert(dst.isRegister());
        // Checkstyle: off
        if (left.kind.isInt()) {
            CiRegister reg = left.asRegister();
            if (right.isConstant()) {
                int val = ((CiConstant) right).asInt();
                masm.mov32BitConstant(ARMV7.r12,val);

                switch (code) {
                  case LogicAnd : //masm.andl(reg, val);
                      masm.iand(reg,reg,ARMV7.r12);

                      //masm.and(ConditionFlag.Always,reg,ARMV7.r12);
                      break;
                   case LogicOr  : //masm.orl(reg, val);
                      masm.ior(reg,reg,ARMV7.r12);
                       break;
                  case LogicXor : //masm.xorl(reg, val);
                      masm.ixor(reg,reg,ARMV7.r12);
                      break;
                    default       : throw Util.shouldNotReachHere();
                }
            } else if (right.isStackSlot()) {
                // added support for stack operands
                CiAddress raddr = frameMap.toStackAddress(((CiStackSlot) right));
                masm.setUpScratch(raddr);
                masm.ldrImmediate(ConditionFlag.Always, 1, 0, 0, ARMV7.r8, ARMV7.r12, 0);
                assert (reg != ARMV7.r12);
                switch (code) {
                    case LogicAnd:// masm.andl(reg, raddr);
                        masm.iand(reg, reg, ARMV7.r8);
                        break;
                    case LogicOr: // masm.orl(reg, raddr);
                        masm.ior(reg, reg, ARMV7.r8);
                        break;
                    case LogicXor: // masm.xorl(reg, raddr);
                        masm.ixor(reg, reg, ARMV7.r8);
                        break;
                    default:
                        throw Util.shouldNotReachHere();
                }
            } else {
                CiRegister rright = right.asRegister();
                switch (code) {
                    case LogicAnd:
                        masm.iand(reg, reg, rright);
                        break;
                    case LogicOr:
                        masm.ior(reg, reg, rright);
                        break;
                    case LogicXor:
                        masm.ixor(reg, reg, rright);
                        break;
                    default:
                        throw Util.shouldNotReachHere();
                }
            }
		
            moveRegs(reg, dst.asRegister(), left.kind, dst.kind);
        } else {
            CiRegister lreg = left.asRegister();
            if (right.isConstant()) {
                CiConstant rightConstant = (CiConstant) right;
                // masm.movq(rscratch1, rightConstant.asLong());
	        masm.movlong(ARMV7.r8,rightConstant.asLong());
                switch (code) {
                    case LogicAnd:// masm.andq(lreg, rscratch1);
                        masm.land(lreg,lreg,ARMV7.r8);
                        break;
                    case LogicOr: masm.lor(lreg,lreg, ARMV7.r8);
                        break;
                    case LogicXor:
                        masm.lxor(lreg,lreg, ARMV7.r8);
                        break;
                    default:
                        throw Util.shouldNotReachHere();
                }
            } else {
                CiRegister rreg = right.asRegister();
                switch (code) {
                    case LogicAnd:
                        masm.land(lreg, lreg, rreg);
                        break;
                    case LogicOr:
                        masm.lor(lreg, lreg, rreg);
                        break;
                    case LogicXor:
                        masm.lxor(lreg, lreg, rreg);
                        break;
                    default:
                        throw Util.shouldNotReachHere();
                }
            }
            CiRegister dreg = dst.asRegister();
            moveRegs(lreg, dreg, left.kind, dst.kind);
        }
        // Checkstyle: on
    }

    void arithmeticIdiv(LIROpcode code, CiValue left, CiValue right, CiValue result, LIRDebugInfo info) {
        assert left.isRegister() : "left must be register";
        assert right.isRegister() || right.isConstant() : "right must be register or constant";
        assert result.isRegister() : "result must be register";

        CiRegister lreg = left.asRegister();
        CiRegister dreg = result.asRegister();
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 24);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 24);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 24);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 24);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 24);
        }
        //assert 0 == 1 : "arithmeticIdiv ARMV7IRAssembler";
        if (right.isConstant()) {
            assert 0 == 1 : "arithmeticIdiv divide by constant not implemented";
            Util.shouldNotReachHere("cwi: I assume this is dead code, notify me if I'm wrong...");

            int divisor = ((CiConstant) right).asInt();
            assert divisor > 0 && CiUtil.isPowerOf2(divisor) : "divisor must be power of two";
            if (code == LIROpcode.Idiv) {
             //   assert lreg == ARMV7.rax : "dividend must be rax";
       //         masm.cdql(); // sign extend into rdx:rax
                if (divisor == 2) {
         //           masm.subl(lreg, ARMV7.rdx);
                } else {
           //         masm.andl(ARMV7.rdx, divisor - 1);
             //       masm.addl(lreg, ARMV7.rdx);
                }
           //     masm.sarl(lreg, CiUtil.log2(divisor));
                moveRegs(lreg, dreg, left.kind, result.kind);
            } else {
                assert code == LIROpcode.Irem;
                Label done = new Label();
                masm.mov(ConditionFlag.Always,false,dreg, lreg);
                //masm.andl(dreg, 0x80000000 | (divisor - 1));
                //masm.jcc(ConditionFlag.positive, done);
                masm.jcc(ConditionFlag.Positive, done);
                masm.decrementl(dreg, 1);
               // masm.orl(dreg, ~(divisor - 1));
                masm.incrementl(dreg, 1);
                masm.bind(done);
            }
        } else {
            CiRegister rreg = right.asRegister();


            //assert lreg == ARMV7.r6 : "left register must be r6-ax";
          //  assert rreg != ARMV7.rdx : "right register must not be rdx";

         //   moveRegs(lreg, ARMV7.rax);

            Label continuation = new Label();

            if (C1XOptions.GenSpecialDivChecks) {
                // check for special case of Integer.MIN_VALUE / -1
                Label normalCase = new Label();
                masm.mov32BitConstant(ARMV7.r12,Integer.MIN_VALUE);
                masm.cmp(ConditionFlag.Always,lreg,ARMV7.r12,0,0);

                masm.jcc(ConditionFlag.NotEqual, normalCase);
                if (code == LIROpcode.Irem) {
                    // prepare X86Register.rdx for possible special case where remainder = 0
                    masm.eor(ConditionFlag.Always,false,ARMV7.r9,ARMV7.r9,ARMV7.r9,0,0);
                }
                masm.mov32BitConstant(ARMV7.r12,-1);
                masm.cmp(ConditionFlag.Always,rreg, ARMV7.r12,0,0);
                masm.jcc(ConditionFlag.Equal, continuation);

                // handle normal case
                masm.bind(normalCase);
            }
           // masm.cdql();
            masm.mov(ConditionFlag.Always,false,ARMV7.r9,lreg);
            int offset = masm.codeBuffer.position();

            masm.sdiv(ConditionFlag.Always,dreg,lreg,rreg);


            // normal and special case exit
            masm.bind(continuation);

            tasm.recordImplicitException(offset, info);
            if (code == LIROpcode.Irem) {
                /*
                (a/b)b + (a%b) = a
                => (a%b) = a - (a/b)b
                r9 = a
                r12 = a
                reg = b
                 */
                masm.mul(ConditionFlag.Always,false,lreg,dreg,rreg);
                masm.sub(ConditionFlag.Always,false,dreg,ARMV7.r9,lreg,0,0);
               // masm.mov(ConditionFlag.Always, false, dreg, ARMV7.r1);
         //       moveRegs(ARMV7.rdx, dreg); // result is in rdx
            } else {
                assert code == LIROpcode.Idiv;
		// Already put the signed division result in the register
         //       moveRegs(ARMV7.rax, dreg);
                  //masm.mov(ConditionFlag.Always,false,dreg,ARMV7.r0);
            }
        }
    }

    void arithmeticIudiv(LIROpcode code, CiValue left, CiValue right, CiValue result, LIRDebugInfo info) {
        assert left.isRegister() : "left must be register";
        assert right.isRegister() : "right must be register";
        assert result.isRegister() : "result must be register";
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 25);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 25);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 25);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 25);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 25);
        }

        CiRegister lreg = left.asRegister();
        CiRegister dreg = result.asRegister();
        CiRegister rreg = right.asRegister();

 	masm.mov(ConditionFlag.Always,false,ARMV7.r9,lreg);
        int offset = masm.codeBuffer.position();

        masm.udiv(ConditionFlag.Always,dreg,lreg,rreg);
	tasm.recordImplicitException(offset, info);



        if (code == LIROpcode.Iurem) {
                /*
                (a/b)b + (a%b) = a
                => (a%b) = a - (a/b)b
                r9 = a
                r12 = a
                reg = b
                 */
                masm.mul(ConditionFlag.Always,false,lreg,dreg,rreg);
                masm.sub(ConditionFlag.Always,false,dreg,ARMV7.r9,lreg,0,0);
               // masm.mov(ConditionFlag.Always, false, dreg, ARMV7.r1);
         //       moveRegs(ARMV7.rdx, dreg); // result is in rdx
        } else {
                assert code == LIROpcode.Iudiv;
                // Already put the usigned division result in the register
         //       moveRegs(ARMV7.rax, dreg);
                  //masm.mov(ConditionFlag.Always,false,dreg,ARMV7.r0);
       }


    }

    void arithmeticLdiv(LIROpcode code, CiValue left, CiValue right, CiValue result, LIRDebugInfo info) {
        assert left.isRegister() : "left must be register";
        assert right.isRegister() : "right must be register";
        assert result.isRegister() : "result must be register";
        assert result.kind.isLong();
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 26);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 26);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 26);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 26);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 26);
        }
        CiRegister lreg = left.asRegister();
        CiRegister dreg = result.asRegister();
        CiRegister rreg = right.asRegister();
        System.out.println("arithmeticLdiv not implemented");
        //assert 0 == 1 : "arithmeticLdiv ARMV7IRAssembler";

        //assert lreg == ARMV7.r0 : "left register must be r0 was rax";

        //  assert rreg != ARMV7.rdx : "right register must not be rdx";
        //masm.mov(ConditionFlag.Always, false, ARMV7.r0, lreg);
        //masm.mov(ConditionFlag.Always, false, ARMV7.r1, ARMV7.cpuRegisters[lreg.encoding+1]);

        //   moveRegs(lreg, ARMV7.rax);

        Label continuation = new Label();

        if (C1XOptions.GenSpecialDivChecks) {
            // check for special case of Long.MIN_VALUE / -1
            Label normalCase = new Label();
            masm.movlong(rreg,java.lang.Long.MIN_VALUE);
     //       masm.movq(ARMV7.rdx, java.lang.Long.MIN_VALUE);
       //     masm.cmpq(ARMV7.rax, ARMV7.rdx);
           // System.out.println("64bit long compares not implemented? arithmeticLDiv");
              masm.jcc(ConditionFlag.NotEqual, normalCase);
            if (code == LIROpcode.Lrem) {
                // prepare X86Register.rdx for possible special case (where remainder = 0)
          //      masm.xorq(ARMV7.rdx, ARMV7.rdx);
                masm.eor(ConditionFlag.Always,false,rreg,rreg,rreg,0,0);
                masm.eor(ConditionFlag.Always,false,ARMV7.cpuRegisters[rreg.encoding+1],
                        ARMV7.cpuRegisters[rreg.encoding+1],ARMV7.cpuRegisters[rreg.encoding+1],0,0);

            }
            masm.cmpl(rreg, -1);
           masm.jcc(ConditionFlag.Equal, continuation);

            // handle normal case
            masm.bind(normalCase);
        }
      //  masm.cdqq();
        int offset = masm.codeBuffer.position();
      //  masm.idivq(rreg);

        // normal and special case exit
        masm.bind(continuation);

        tasm.recordImplicitException(offset, info);
        if (code == LIROpcode.Lrem) {
        //    moveRegs(ARMV7.rdx, dreg);
        } else {
            assert code == LIROpcode.Ldiv;
          //  moveRegs(ARMV7.rax, dreg);
        }
	masm.mov32BitConstant(ARMV7.r12,4);
	masm.blx(ARMV7.r12);

    }

    void arithmeticLudiv(LIROpcode code, CiValue left, CiValue right, CiValue result, LIRDebugInfo info) {
        assert left.isRegister() : "left must be register";
        assert right.isRegister() : "right must be register";
        assert result.isRegister() : "result must be register";
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 27);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 27);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 27);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 27);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 27);
        }
        System.out.println("arithmeticLudiv not implemented");

        CiRegister lreg = left.asRegister();
        CiRegister dreg = result.asRegister();
        CiRegister rreg = right.asRegister();

        //assert 0 == 1 : "arithmeticLudiv ARMV7IRAssembler";

        //   assert lreg == ARMV7.rax : "left register must be rax";
      //  assert rreg != ARMV7.rdx : "right register must not be rdx";
       //
        // Must zero the high 64-bit word (in RDX) of the dividend
     //   masm.xorq(ARMV7.rdx, ARMV7.rdx);

    //    moveRegs(lreg, ARMV7.rax);

        int offset = masm.codeBuffer.position();
    //    masm.divq(rreg);

        tasm.recordImplicitException(offset, info);
        if (code == LIROpcode.Lurem) {
      //      moveRegs(ARMV7.rdx, dreg);
        } else {
            assert code == LIROpcode.Ludiv;
        //    moveRegs(ARMV7.rax, dreg);
        }
	masm.mov32BitConstant(ARMV7.r12,12);
	masm.blx(ARMV7.r12);
    }
private ConditionFlag convertCondition(Condition condition) {
        ConditionFlag acond = ConditionFlag.NeverUse;
        switch (condition) {
            case EQ : acond = ConditionFlag.Equal; /*ConditionFlag.equal;*/ break;
            case NE : acond = ConditionFlag.NotEqual; /*notEqual;*/ break;

            case LT : acond = ConditionFlag.SignedLesser; /*less;*/ break;
            case LE : acond = ConditionFlag.SignedLowerOrEqual; /*lessEqual;*/ break;
            case GE : acond = ConditionFlag.SignedGreaterOrEqual; /*greaterEqual;*/ break;
            case GT : acond = ConditionFlag.SignedGreater; /*greater;*/ break;
            case BE : acond = ConditionFlag.UnsignedLowerOrEqual; /*belowEqual;*/ break;
            case AE : acond = ConditionFlag.CarrySetUnsignedHigherEqual; /*aboveEqual;*/ break;
            case BT : acond = ConditionFlag.CarryClearUnsignedLower; /*below;*/ break;
            case AT : acond = ConditionFlag.UnsignedHigher; /*above;*/ break;
            default : throw Util.shouldNotReachHere();

        }
        return acond;
    }
    @Override
    protected void emitCompare(Condition condition, CiValue opr1, CiValue opr2, LIROp2 op) {
        // Checkstyle: off
        assert Util.archKindsEqual(opr1.kind.stackKind(), opr2.kind.stackKind()) || (opr1.kind == CiKind.Long && opr2.kind == CiKind.Int) : "nonmatching stack kinds (" + condition + "): " + opr1.kind.stackKind() + "==" + opr2.kind.stackKind();

        if (opr1.isConstant()) {
            // Use scratch register
            CiValue newOpr1 = compilation.registerConfig.getScratchRegister().asValue(opr1.kind);
            const2reg(opr1, newOpr1, null);
            opr1 = newOpr1;
        }

        if (opr1.isRegister()) {
            CiRegister reg1 = opr1.asRegister();
            if (opr2.isRegister()) {
                // register - register
                switch (opr1.kind) {
                    case Boolean:
                    case Byte:
                    case Char:
                    case Short:
                    case Int:
                        masm.cmpl(reg1, opr2.asRegister());
                        break;
                    case Object:
                        masm.cmpq(reg1, opr2.asRegister());
                        break;
                    case Long:
                        masm.lcmpl(convertCondition(condition),reg1, opr2.asRegister());
                        break;
                    case Float:
                        // was reg1 but need to hack it to use the correct float reg!!
                        masm.ucomisd(asXmmFloatReg(opr1), asXmmFloatReg(opr2));
                        // was ucomiss but our encoding can
                        // handle single or double precision
                        // as long as the FP regs s0 d0 usage is fixed.
                        break;
                    case Double:
                        masm.ucomisd(reg1, asXmmDoubleReg(opr2));
                        break;
                    default:
                        throw Util.shouldNotReachHere(opr1.kind.toString());
                }
            } else if (opr2.isStackSlot()) {
                // register - stack

                CiStackSlot opr2Slot = (CiStackSlot) opr2;
                switch (opr1.kind) {
                    case Boolean :
                    case Byte    :
                    case Char    :
                    case Short   :
                    case Int     : //masm.cmpl(reg1, frameMap.toStackAddress(opr2Slot)); break;
                        masm.cmpl(reg1,frameMap.toStackAddress(opr2Slot));
                        break;
                    case Long    :
			            masm.setUpScratch(frameMap.toStackAddress(opr2Slot));
			            masm.ldrd(ConditionFlag.Always,ARMV7.r8,ARMV7.r12,0);
			            masm.lcmpl(convertCondition(condition),reg1,ARMV7.r8);
                        break;
                    case Object  : masm.cmpptr(reg1, frameMap.toStackAddress(opr2Slot)); break;
                    case Float   : //masm.ucomiss(reg1, frameMap.toStackAddress(opr2Slot));
                        masm.setUpScratch(frameMap.toStackAddress(opr2Slot));
                        masm.vldr(ConditionFlag.Always,ARMV7.s30,ARMV7.r12,0);
                        masm.ucomisd(reg1, ARMV7.s30);
                        break;
                    case Double:
                        masm.setUpScratch(frameMap.toStackAddress(opr2Slot));
                        masm.vldr(ConditionFlag.Always,ARMV7.d15,ARMV7.r12,0);
                        masm.ucomisd(reg1, ARMV7.d15);
                     break;
                    default      : throw Util.shouldNotReachHere();
                }
            } else if (opr2.isConstant()) {
                // register - constant
                CiConstant c = (CiConstant) opr2;

                switch (opr1.kind) {
                    case Boolean :
                    case Byte    :
                    case Char    :
                    case Short   :
                    case Int     : masm.cmpl(reg1, c.asInt()); break;
                    case Float   : //masm.ucomiss(reg1, tasm.recordDataReferenceInCode(CiConstant.forFloat(((CiConstant) opr2).asFloat())));
                        masm.setUpScratch(tasm.recordDataReferenceInCode(CiConstant.forFloat(((CiConstant) opr2).asFloat())));
                        masm.addRegisters(ConditionFlag.Always,false,ARMV7.r12,ARMV7.r12,ARMV7.r15,0,0);
                        masm.vldr(ConditionFlag.Always,ARMV7.s30,ARMV7.r12,0);
                        masm.ucomisd(reg1, ARMV7.s30);

                        break;
                    case Double  : //masm.ucomisd(reg1, tasm.recordDataReferenceInCode(CiConstant.forDouble(((CiConstant) opr2).asDouble())));
                        masm.setUpScratch(tasm.recordDataReferenceInCode(CiConstant.forDouble(((CiConstant) opr2).asDouble())));
                        masm.addRegisters(ConditionFlag.Always,false,ARMV7.r12,ARMV7.r12,ARMV7.r15,0,0);
                        masm.vldr(ConditionFlag.Always,ARMV7.d15,ARMV7.r12,0);
                        masm.ucomisd(reg1, ARMV7.d15);
                        break;
                    case Long    : { //assert 0 == 1;
			//System.out.println("emitCompare assert commented out --- not implemented");
                        if (c.asLong() == 0) {
                           // masm.cmpq(reg1, 0);
			        masm.movlong(ARMV7.r8, 0);

                        } else {
                            //masm.movq(rscratch1, c.asLong());
			        masm.movlong(ARMV7.r8, c.asLong());

                            //masm.cmpq(reg1, rscratch1);

                        }
			masm.lcmpl(convertCondition(condition),reg1,ARMV7.r8);
                        break;
                    }
                    case Object  :  {
                        movoop(rscratch1, c);
                        masm.cmpq(reg1, rscratch1);
                        break;
                    }
                    default      : throw Util.shouldNotReachHere();
                }
            } else {
                throw Util.shouldNotReachHere();
            }
        } else if (opr1.isStackSlot()) {
            CiAddress left = asAddress(opr1);
            if (opr2.isConstant()) {
                CiConstant right = (CiConstant) opr2;
                // stack - constant
                assert  0 == 1 : "stack constant ";
                switch (opr1.kind) {
                    case Boolean :
                    case Byte    :
                    case Char    :
                    case Short   :
                    case Int     : //masm.cmpl(left, right.asInt()); break;
                    case Long    : assert NumUtil.isInt(right.asLong());
                                  // masm.cmpq(left, right.asInt()); break;
                    case Object  : assert right.isNull();
                                   //masm.cmpq(left, 0); break;
                    default      : throw Util.shouldNotReachHere();
                }
            } else {
                throw Util.shouldNotReachHere();
            }

        } else {
            throw Util.shouldNotReachHere(opr1.toString() + " opr2 = " + opr2);
        }
        // Checkstyle: on
    }

    @Override
    protected void emitCompare2Int(LIROpcode code, CiValue left, CiValue right, CiValue dst, LIROp2 op) {
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 28);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 28);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 28);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 28);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 28);
        }

        if (code == LIROpcode.Cmpfd2i || code == LIROpcode.Ucmpfd2i) {
            if (left.kind.isFloat()) {
                masm.cmpss2int(asXmmFloatReg(left), asXmmFloatReg(right), dst.asRegister(), code == LIROpcode.Ucmpfd2i);
            } else if (left.kind.isDouble()) {
                masm.cmpsd2int(asXmmDoubleReg(left), asXmmDoubleReg(right), dst.asRegister(), code == LIROpcode.Ucmpfd2i);
            } else {
                throw Util.unimplemented("no fpu stack");
            }
        } else {
            assert code == LIROpcode.Cmpl2i;
            CiRegister dest = dst.asRegister();
            Label high = new Label();
            Label done = new Label();
            Label isEqual = new Label();
            masm.cmpptr(left.asRegister(), right.asRegister());
            //masm.jcc(ConditionFlag.equal, isEqual);
            masm.jcc(ConditionFlag.Equal,isEqual);
           // masm.jcc(ConditionFlag.greater, high);
            masm.jcc(ConditionFlag.SignedGreater,high); // unsigned Greater?
            masm.xorptr(dest, dest);
            masm.decrementl(dest, 1);
            masm.jmp(done);
            masm.bind(high);
            masm.xorptr(dest, dest);
            masm.incrementl(dest, 1);
            masm.jmp(done);
            masm.bind(isEqual);
            masm.xorptr(dest, dest);
            masm.bind(done);
        }
    }

    @Override
    protected void emitDirectCallAlignment() {
        masm.alignForPatchableDirectCall();
    }

    @Override
    protected void emitIndirectCall(Object target, LIRDebugInfo info, CiValue callAddress) {
        CiRegister reg = rscratch1;
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r8, 29);
            masm.movw(ConditionFlag.Always, ARMV7.r8, 29);
            masm.movw(ConditionFlag.Always, ARMV7.r8, 29);
            masm.movw(ConditionFlag.Always, ARMV7.r8, 29);
            masm.movw(ConditionFlag.Always, ARMV7.r8, 29);
        }
        if (callAddress.isRegister()) {
            reg = callAddress.asRegister();
        } else {
            moveOp(callAddress, reg.asValue(callAddress.kind), callAddress.kind, null, false);
        }
        masm.mov32BitConstant(ARMV7.r8,8);
        indirectCall(reg, target, info);
    }

    @Override
    protected void emitDirectCall(Object target, LIRDebugInfo info) {
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 30);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 30);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 30);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 30);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 30);
        }
        directCall(target, info);
    }

    @Override
    protected void emitNativeCall(String symbol, LIRDebugInfo info, CiValue callAddress) {
        CiRegister reg = rscratch1;
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 31);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 31);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 31);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 31);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 31);
        }

        if (callAddress.isRegister()) {
            reg = callAddress.asRegister();
        } else {
            moveOp(callAddress, reg.asValue(callAddress.kind), callAddress.kind, null, false);
        }
	masm.mov32BitConstant(ARMV7.r8,0);
        indirectCall(reg, symbol, info);
    }

    @Override
    protected void emitThrow(CiValue exceptionPC, CiValue exceptionOop, LIRDebugInfo info, boolean unwind) {
       // exception object is not added to oop map by LinearScan
       // (LinearScan assumes that no oops are in fixed registers)
       // info.addRegisterOop(exceptionOop);
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 32);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 32);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 32);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 32);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 32);
        }

        directCall(unwind ? CiRuntimeCall.UnwindException : CiRuntimeCall.HandleException, info);
        // enough room for two byte trap
        if (!C1XOptions.EmitNopAfterCall) {
            masm.nop();
        }
    }

    private void emitXIRShiftOp(LIROpcode code, CiValue left, CiValue count, CiValue dest) {
        if (count.isConstant()) {
            emitShiftOp(code, left, ((CiConstant) count).asInt(), dest);
        } else {
            emitShiftOp(code, left, count, dest, IllegalValue);
        }
    }

    @Override
    protected void emitShiftOp(LIROpcode code, CiValue left, CiValue count, CiValue dest, CiValue tmp) {
        // optimized version for linear scan:
        // * count must be already in ECX (guaranteed by LinearScan)
        // * left and dest must be equal
        // * tmp must be unused
        assert count.asRegister() == SHIFTCount : "count must be in r8";
        assert left == dest : "left and dest must be equal";
        assert tmp.isIllegal() : "wasting a register if tmp is allocated";
        assert left.isRegister();

        if (left.kind.isInt()) {
            CiRegister value = left.asRegister();
            assert value != SHIFTCount : "left cannot be r8";

            // Checkstyle: off
            switch (code) {
                  case Shl  : masm.ishl(dest.asRegister(), value, count.asRegister()); break;
                  case Shr  : masm.ishr(dest.asRegister(), value, count.asRegister()); break;
                  case Ushr : masm.iushr(dest.asRegister(), value, count.asRegister()); break;
                default   : throw Util.shouldNotReachHere();
            }
        } else {
            CiRegister lreg = left.asRegister();
            assert lreg != SHIFTCount : "left cannot be r8";
            switch (code) {
                case Shl  : masm.lshl(dest.asRegister(), lreg, count.asRegister());
                    break;
                case Shr  : masm.lshr(dest.asRegister(), lreg, count.asRegister());
                    break;
                case Ushr : masm.lushr(dest.asRegister(), lreg, count.asRegister());
                    break;
                default   : throw Util.shouldNotReachHere();
            }
            // Checkstyle: on
        }
    }

    @Override
    protected void emitShiftOp(LIROpcode code, CiValue left, int count, CiValue dest) {
        assert dest.isRegister();
        if (dest.kind.isInt()) {
            // first move left into dest so that left is not destroyed by the shift
            CiRegister value = dest.asRegister();
            count = count & 0x1F; // Java spec


            masm.mov(ConditionFlag.Always,false,dest.asRegister(),left.asRegister());
            // Checkstyle: off
            masm.mov32BitConstant(ARMV7.r12,count);
	    //System.out.println("emitShiftOp DEST is " + dest.asRegister().encoding);
            switch (code) {
                case Shl  : masm.ishl(dest.asRegister(), value, ARMV7.r12); break;
                case Shr  : //masm.sarl(value, count);
                            masm.ishr(dest.asRegister(),value,ARMV7.r12);
                 break;
                case Ushr : //masm.shrl(value, count);
                          masm.iushr(dest.asRegister(),value,ARMV7.r12);
                 break;

                default   : throw Util.shouldNotReachHere();
            }
        } else {

            // first move left into dest so that left is not destroyed by the shift
            CiRegister value = dest.asRegister();
            count = count & 0x1F; // Java spec

            moveRegs(left.asRegister(), value, left.kind, dest.kind);
            masm.mov32BitConstant(ARMV7.r12,count);
            switch (code) {
                case Shl  : //masm.shlq(value, count);
                           masm.lshl(value, left.asRegister(), ARMV7.r12);
                    break;
                case Shr  : //masm.sarq(value, count);
			   masm.lshr(value, left.asRegister(), ARMV7.r12);
                    break;
               case Ushr : //masm.shrq(value, count);
                          masm.lushr(value, left.asRegister(), ARMV7.r12);
                   break;
                default   : throw Util.shouldNotReachHere();
            }
            // Checkstyle: on
        }
    }

    @Override
    protected void emitSignificantBitOp(boolean most, CiValue src, CiValue dst) {
        assert dst.isRegister();
        CiRegister result = dst.asRegister();
        masm.xorq(result, result);
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 33);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 33);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 33);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 33);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 33);
        }

        //   masm.notq(result); // twos complement?
	masm.rsb(ConditionFlag.Always,false,result,result,0,0); // negate
	masm.incq(result); // add 1 to get to the twos completent
        if (src.isRegister()) {
            CiRegister value = src.asRegister();
            assert value != result;
	    //System.out.println("CHECK Semantics of clz versus bsrq concerning reutrn value  BITOPS emitSignificantBitOp");
            if (most) {

                  masm.clz(ConditionFlag.Always,result,value);
		// NOTE wILL RETURN 32 if zero!!!
       //         masm.bsrq(result, value);
            } else {
                  masm.rbit(ConditionFlag.Always,ARMV7.r12,value);
                  masm.clz(ConditionFlag.Always,result,ARMV7.r12);
        //        masm.bsfq(result, value);
            }
        } else {
            CiAddress laddr = asAddress(src);
	        masm.setUpScratch(laddr);
            masm.ldrImmediate(ConditionFlag.Always,1,0,0,ARMV7.r12,ARMV7.r12,0);
            if (most) {
                  masm.clz(ConditionFlag.Always,result,ARMV7.r12);
        //        masm.bsrq(result, laddr);
            } else {
        //        masm.bsfq(result, laddr);
                  masm.rbit(ConditionFlag.Always,ARMV7.r12,ARMV7.r12);
                  masm.clz(ConditionFlag.Always,result,ARMV7.r12);
            }
        }
    }

    @Override
    protected void emitAlignment() {
        masm.align(target.wordSize);
    }

    @Override
    protected void emitNegate(LIRNegate op) {
        CiValue left = op.operand();
        CiValue dest = op.result();
        assert left.isRegister();
        if (left.kind.isInt()) {
            masm.ineg(dest.asRegister(), left.asRegister());
        } else if (dest.kind.isFloat()) {
            if (asXmmFloatReg(left) != asXmmFloatReg(dest)) {
                masm.movflt(asXmmFloatReg(dest), asXmmFloatReg(left));
            }
            callStub(op.stub, null, asXmmFloatReg(dest), dest);
        } else if (dest.kind.isDouble()) {
            if (asXmmDoubleReg(left) != asXmmDoubleReg(dest)) {
                masm.movdbl(asXmmDoubleReg(dest), asXmmDoubleReg(left));
            }
            callStub(op.stub, null, asXmmDoubleReg(dest), dest);
        } else {
            masm.lneg(dest.asRegister(), left.asRegister());
        }
    }

    @Override
    protected void emitLea(CiValue src, CiValue dest) {
        CiRegister reg = dest.asRegister();
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 34);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 34);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 34);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 34);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 34);
        }
        masm.leaq(reg, asAddress(src));
    }

    @Override
    protected void emitNullCheck(CiValue src, LIRDebugInfo info) {
        assert src.isRegister();
        if (C1XOptions.NullCheckUniquePc) {
            masm.nop();
        }
        tasm.recordImplicitException(codePos(), info);
         masm.nullCheck(src.asRegister());
    }

    @Override
    protected void emitVolatileMove(CiValue src, CiValue dest, CiKind kind, LIRDebugInfo info) {
        assert kind == CiKind.Long : "only for volatile long fields";

        if (info != null) {
            tasm.recordImplicitException(codePos(), info);
        }
        assert 0 == 1 : "emitVolatileMove ARMV7IRAssembler";

        if (src.kind.isDouble()) {
            if (dest.isRegister()) {
          //      masm.movdq(dest.asRegister(), asXmmDoubleReg(src));
            } else if (dest.isStackSlot()) {
               // masm.movsd(frameMap.toStackAddress((CiStackSlot) dest), asXmmDoubleReg(src));
            } else {
                assert dest.isAddress();
                //masm.movsd((CiAddress) dest, asXmmDoubleReg(src));
            }
        } else {
            assert dest.kind.isDouble();
            if (src.isStackSlot()) {
                masm.movdbl(asXmmDoubleReg(dest), frameMap.toStackAddress((CiStackSlot) src));
            } else {
                assert src.isAddress();
                masm.movdbl(asXmmDoubleReg(dest), (CiAddress) src);
            }
        }
    }

    private static CiRegister asXmmDoubleReg(CiValue dest) {
        assert dest.kind.isDouble() : "must be double XMM register";
        CiRegister result = dest.asRegister();
	if(result.number > 31) {
		System.err.println("ARMV7LIRAssembler asXmmDoubleReg is a float!!!! bodging to D15");
		result = ARMV7.d15;
        }
        assert result.isFpu() : "must be XMM register";
        return result;
    }

    @Override
    protected void emitMemoryBarriers(int barriers) {
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 35);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 35);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 35);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 35);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 35);
        }
        masm.membar(barriers);
    }

    @Override
    protected void doPeephole(LIRList list) {
        // Do nothing for now
    }

    @Override
    protected void emitXir(LIRXirInstruction instruction) {
        XirSnippet snippet = instruction.snippet;

        Label[] labels = new Label[snippet.template.labels.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = new Label();
        }
        emitXirInstructions(instruction, snippet.template.fastPath, labels, instruction.getOperands(), snippet.marks);
        if (snippet.template.slowPath != null) {
            addSlowPath(new SlowPath(instruction, labels, snippet.marks));
        }
    }

    @Override
    protected void emitSlowPath(SlowPath sp) {
        int start = -1;
        if (C1XOptions.TraceAssembler) {
            TTY.println("Emitting slow path for XIR instruction " + sp.instruction.snippet.template.name);
            start = masm.codeBuffer.position();
        }
        emitXirInstructions(sp.instruction, sp.instruction.snippet.template.slowPath, sp.labels, sp.instruction.getOperands(), sp.marks);
        masm.nop();
        if (C1XOptions.TraceAssembler) {
            TTY.println("From " + start + " to " + masm.codeBuffer.position());
        }
    }

    public void emitXirInstructions(LIRXirInstruction xir, XirInstruction[] instructions, Label[] labels, CiValue[] operands, Map<XirMark, Mark> marks) {
        LIRDebugInfo info = xir == null ? null : xir.info;
        LIRDebugInfo infoAfter = xir == null ? null : xir.infoAfter;

        for (XirInstruction inst : instructions) {
            switch (inst.op) {
                case Add:
                    emitArithOp(LIROpcode.Add, operands[inst.x().index], operands[inst.y().index], operands[inst.result.index], null);
                    break;

                case Sub:
                    emitArithOp(LIROpcode.Sub, operands[inst.x().index], operands[inst.y().index], operands[inst.result.index], null);
                    break;

                case Div:
                    if (inst.kind == CiKind.Int) {
                        arithmeticIdiv(LIROpcode.Idiv, operands[inst.x().index], operands[inst.y().index], operands[inst.result.index], null);
                    } else {
                        emitArithOp(LIROpcode.Div, operands[inst.x().index], operands[inst.y().index], operands[inst.result.index], null);
                    }
                    break;

                case Mul:
                    emitArithOp(LIROpcode.Mul, operands[inst.x().index], operands[inst.y().index], operands[inst.result.index], null);
                    break;

                case Mod:
                    if (inst.kind == CiKind.Int) {
                        arithmeticIdiv(LIROpcode.Irem, operands[inst.x().index], operands[inst.y().index], operands[inst.result.index], null);
                    } else {
                        emitArithOp(LIROpcode.Rem, operands[inst.x().index], operands[inst.y().index], operands[inst.result.index], null);
                    }
                    break;

                case Shl:
                    emitXIRShiftOp(LIROpcode.Shl, operands[inst.x().index], operands[inst.y().index], operands[inst.result.index]);
                    break;

                case Sar:
                    emitXIRShiftOp(LIROpcode.Shr, operands[inst.x().index], operands[inst.y().index], operands[inst.result.index]);
                    break;

                case Shr:
                    emitXIRShiftOp(LIROpcode.Ushr, operands[inst.x().index], operands[inst.y().index], operands[inst.result.index]);
                    break;

                case And:
                    emitLogicOp(LIROpcode.LogicAnd, operands[inst.x().index], operands[inst.y().index], operands[inst.result.index]);
                    break;

                case Or:
                    emitLogicOp(LIROpcode.LogicOr, operands[inst.x().index], operands[inst.y().index], operands[inst.result.index]);
                    break;

                case Xor:
                    emitLogicOp(LIROpcode.LogicXor, operands[inst.x().index], operands[inst.y().index], operands[inst.result.index]);
                    break;

                case Mov: {
                    CiValue result = operands[inst.result.index];

                    CiValue source = operands[inst.x().index];
                    moveOp(source, result, result.kind, null, false);
                    break;
                }

                case PointerLoad: {
                    if ((Boolean) inst.extra && info != null) {
                        tasm.recordImplicitException(codePos(), info);
                    }

                    CiValue result = operands[inst.result.index];
                    CiValue pointer = operands[inst.x().index];
                    CiRegisterValue register = assureInRegister(pointer);
                    moveOp(new CiAddress(inst.kind, register, 0), result, inst.kind, null, false);
                    break;
                }

                case PointerStore: {
                    if ((Boolean) inst.extra && info != null) {
                        tasm.recordImplicitException(codePos(), info);
                    }

                    CiValue value = operands[inst.y().index];
                    CiValue pointer = operands[inst.x().index];
                    assert pointer.isVariableOrRegister();
                    moveOp(value, new CiAddress(inst.kind, pointer, 0), inst.kind, null, false);
                    break;
                }

                case PointerLoadDisp: {
                    CiXirAssembler.AddressAccessInformation addressInformation = (CiXirAssembler.AddressAccessInformation) inst.extra;
                    boolean canTrap = addressInformation.canTrap;

                    CiAddress.Scale scale = addressInformation.scale;
                    int displacement = addressInformation.disp;

                    CiValue result = operands[inst.result.index];
                    CiValue pointer = operands[inst.x().index];
                    CiValue index = operands[inst.y().index];

                    pointer = assureInRegister(pointer);
                    assert pointer.isVariableOrRegister();

                    CiValue src = null;
                    if (index.isConstant()) {
                        assert index.kind == CiKind.Int;
                        CiConstant constantIndex = (CiConstant) index;
                        src = new CiAddress(inst.kind, pointer, constantIndex.asInt() * scale.value + displacement);
                    } else {
                        src = new CiAddress(inst.kind, pointer, index, scale, displacement);
                    }

                    moveOp(src, result, inst.kind, canTrap ? info : null, false);
                    break;
                }
                case Here: {
                    CiValue result = operands[inst.result.index];
                    CiRegister dst = result.asRegister();
                    int beforeLea = masm.codeBuffer.position();
                    masm.leaq(dst, new CiAddress(target.wordKind, ARMV7.r15.asValue(), 0)); // was rip not r15
                    int afterLea = masm.codeBuffer.position();
                    masm.codeBuffer.setPosition(beforeLea);
                    masm.leaq(dst, new CiAddress(target.wordKind, ARMV7.r15.asValue(), beforeLea - afterLea)); // was rip
                    break;
                }

                case LoadEffectiveAddress: {
                    CiXirAssembler.AddressAccessInformation addressInformation = (CiXirAssembler.AddressAccessInformation) inst.extra;

                    CiAddress.Scale scale = addressInformation.scale;
                    int displacement = addressInformation.disp;

                    CiValue result = operands[inst.result.index];
                    CiValue pointer = operands[inst.x().index];
                    CiValue index = operands[inst.y().index];

                    pointer = assureInRegister(pointer);
                    assert pointer.isVariableOrRegister();
                    CiValue src = new CiAddress(CiKind.Illegal, pointer, index, scale, displacement);
                    emitLea(src, result);
                    break;
                }

                case PointerStoreDisp: {
                    CiXirAssembler.AddressAccessInformation addressInformation = (CiXirAssembler.AddressAccessInformation) inst.extra;
                    boolean canTrap = addressInformation.canTrap;

                    CiAddress.Scale scale = addressInformation.scale;
                    int displacement = addressInformation.disp;

                    CiValue value = operands[inst.z().index];
                    CiValue pointer = operands[inst.x().index];
                    CiValue index = operands[inst.y().index];

                    pointer = assureInRegister(pointer);
                    assert pointer.isVariableOrRegister();

                    CiValue dst;
                    if (index.isConstant()) {
                        assert index.kind == CiKind.Int;
                        CiConstant constantIndex = (CiConstant) index;
                        dst = new CiAddress(inst.kind, pointer, IllegalValue, scale, constantIndex.asInt() * scale.value + displacement);
                    } else {
                        dst = new CiAddress(inst.kind, pointer, index, scale, displacement);
                    }

                    moveOp(value, dst, inst.kind, canTrap ? info : null, false);
                    break;
                }

                case RepeatMoveBytes:
                    assert 0 == 1 : "RepeatMoveBytes ARMV7LIRAssembler";
                  //  assert operands[inst.x().index].asRegister().equals(ARMV7.rsi) : "wrong input x: " + operands[inst.x().index];
                   // assert operands[inst.y().index].asRegister().equals(ARMV7.rdi) : "wrong input y: " + operands[inst.y().index];
                   // assert operands[inst.z().index].asRegister().equals(ARMV7.rcx) : "wrong input z: " + operands[inst.z().index];
                //    masm.repeatMoveBytes();
                    break;

                case RepeatMoveWords:
                    assert 0 == 1 : "RepeatMoveWords ARMV7LIRAssembler";
                  //  assert operands[inst.x().index].asRegister().equals(ARMV7.rsi) : "wrong input x: " + operands[inst.x().index];
                  //  assert operands[inst.y().index].asRegister().equals(ARMV7.rdi) : "wrong input y: " + operands[inst.y().index];
                   // assert operands[inst.z().index].asRegister().equals(ARMV7.rcx) : "wrong input z: " + operands[inst.z().index];
                 //   masm.repeatMoveWords();
                    break;

                case PointerCAS:
                    assert 0 == 1 : "PointerCAS ARMV7LIRAssembler";
                    if ((Boolean) inst.extra && info != null) {
                        tasm.recordImplicitException(codePos(), info);
                    }
                  //  assert operands[inst.x().index].asRegister().equals(ARMV7.rax) : "wrong input x: " + operands[inst.x().index];

                    CiValue exchangedVal = operands[inst.y().index];
                    CiValue exchangedAddress = operands[inst.x().index];
                    CiRegisterValue pointerRegister = assureInRegister(exchangedAddress);
                    CiAddress addr = new CiAddress(target.wordKind, pointerRegister);
                 //   masm.cmpxchgq(exchangedVal.asRegister(), addr);

                    break;

                case CallStub: {
                    XirTemplate stubId = (XirTemplate) inst.extra;
                    CiRegister result = CiRegister.None;
                    if (inst.result != null) {
                        result = operands[inst.result.index].asRegister();
                    }
                    CiValue[] args = new CiValue[inst.arguments.length];
                    for (int i = 0; i < args.length; i++) {
                        args[i] = operands[inst.arguments[i].index];
                    }
                    callStub(stubId, info, result, args);
                    break;
                }
                case CallRuntime: {
                    if (DEBUG_MOVS) {
                        masm.movw(ConditionFlag.Always, ARMV7.r12, 36);
                        masm.movw(ConditionFlag.Always, ARMV7.r12, 36);
                        masm.movw(ConditionFlag.Always, ARMV7.r12, 36);
                        masm.movw(ConditionFlag.Always, ARMV7.r12, 36);
                        masm.movw(ConditionFlag.Always, ARMV7.r12, 36);
                    }

                    CiKind[] signature = new CiKind[inst.arguments.length];
                    for (int i = 0; i < signature.length; i++) {
                        signature[i] = inst.arguments[i].kind;
                    }

                    CiCallingConvention cc = frameMap.getCallingConvention(signature, RuntimeCall);
                    for (int i = 0; i < inst.arguments.length; i++) {
                        CiValue argumentLocation = cc.locations[i];
                        CiValue argumentSourceLocation = operands[inst.arguments[i].index];
                        if (argumentLocation != argumentSourceLocation) {
                            moveOp(argumentSourceLocation, argumentLocation, argumentLocation.kind, null, false);
                        }
                    }

                    RuntimeCallInformation runtimeCallInformation = (RuntimeCallInformation) inst.extra;
                    directCall(runtimeCallInformation.target, (runtimeCallInformation.useInfoAfter) ? infoAfter : info);

                    if (inst.result != null && inst.result.kind != CiKind.Illegal && inst.result.kind != CiKind.Void) {
                        CiRegister returnRegister = compilation.registerConfig.getReturnRegister(inst.result.kind);
                        CiValue resultLocation = returnRegister.asValue(inst.result.kind.stackKind());
                        moveOp(resultLocation, operands[inst.result.index], inst.result.kind.stackKind(), null, false);
                    }
                    break;
                }
                case Jmp: {
                    if (inst.extra instanceof XirLabel) {
                        Label label = labels[((XirLabel) inst.extra).index];
                        masm.jmp(label);
                    } else {
                        directJmp(inst.extra);
                    }
                    break;
                }
                case DecAndJumpNotZero: {
                    assert 0 == 1 : "DecAndJumpNotZero ARMV7LIRAssembler";
                    Label label = labels[((XirLabel) inst.extra).index];
                    CiValue value = operands[inst.x().index];
                    if (value.kind == CiKind.Long) {
                        masm.decq(value.asRegister());
                    } else {
                        assert value.kind == CiKind.Int;
                   //     masm.decl(value.asRegister());
                    }
                    //masm.jcc(ConditionFlag.notZero, label);
                    break;
                }
                // TODO check all the conditions!!!!!
                case Jeq: {
                    Label label = labels[((XirLabel) inst.extra).index];
                   emitXirCompare(inst, Condition.EQ, ConditionFlag.Equal, operands, label);
                    break;
                }
                case Jneq: {
                    Label label = labels[((XirLabel) inst.extra).index];
                    emitXirCompare(inst, Condition.NE, ConditionFlag.NotEqual, operands, label);
                    break;
                }

                case Jgt: {
                    Label label = labels[((XirLabel) inst.extra).index];
                    emitXirCompare(inst, Condition.GT, ConditionFlag.SignedGreater, operands, label); // was Greater?
                    break;
                }

                case Jgteq: {
                    Label label = labels[((XirLabel) inst.extra).index];
                   emitXirCompare(inst, Condition.GE, ConditionFlag.SignedGreaterOrEqual, operands, label); // was greaterEqual
                    break;
                }

                case Jugteq: {
                    Label label = labels[((XirLabel) inst.extra).index];
                   //emitXirCompare(inst, Condition.AE, ConditionFlag.aboveEqual, operands, label);

                    emitXirCompare(inst, Condition.AE, ConditionFlag.CarrySetUnsignedHigherEqual, operands, label);  // negation in the XirCompare?

                    break;
                }

                case Jlt: {
                    Label label = labels[((XirLabel) inst.extra).index];
                    emitXirCompare(inst, Condition.LT, ConditionFlag.SignedLesser, operands, label);
                    break;
                }

                case Jlteq: {
                    Label label = labels[((XirLabel) inst.extra).index];
                    emitXirCompare(inst, Condition.LE, ConditionFlag.SignedLowerOrEqual, operands, label);
                    break;
                }

                case Jbset: {
                    Label label = labels[((XirLabel) inst.extra).index];
                    CiValue pointer = operands[inst.x().index];
                    CiValue offset = operands[inst.y().index];
                    CiValue bit = operands[inst.z().index];
                    assert offset.isConstant() && bit.isConstant();
                    CiConstant constantOffset = (CiConstant) offset;
                    CiConstant constantBit = (CiConstant) bit;
                    CiAddress src = new CiAddress(inst.kind, pointer, constantOffset.asInt());
		    assert(0==1);
                  //  masm.btli(src, constantBit.asInt());
                    //masm.jcc(ConditionFlag.aboveEqual, label);
                    masm.jcc(ConditionFlag.SignedGreaterOrEqual,label);
                    break;
                }

                case Bind: {
                    XirLabel l = (XirLabel) inst.extra;
                    Label label = labels[l.index];
                    asm.bind(label);
                    break;
                }
                case Safepoint: {
                    assert info != null : "Must have debug info in order to create a safepoint.";
                    tasm.recordSafepoint(codePos(), info);
                    break;
                }
                case NullCheck: {
                    tasm.recordImplicitException(codePos(), info);
                    CiValue pointer = operands[inst.x().index];
                    masm.nullCheck(pointer.asRegister());
                    break;
                }
                case Align: {
                    masm.align((Integer) inst.extra);
                    break;
                }
                case StackOverflowCheck: {
                    int frameSize = initialFrameSizeInBytes();
                    int lastFramePage = frameSize / target.pageSize;
                    // emit multiple stack bangs for methods with frames larger than a page
                    for (int i = 0; i <= lastFramePage; i++) {
                        int offset = (i + C1XOptions.StackShadowPages) * target.pageSize;
                        // Deduct 'frameSize' to handle frames larger than the shadow
                        bangStackWithOffset(offset - frameSize);
                    }


                    break;
                }
                case PushFrame: {
                    int frameSize = initialFrameSizeInBytes();
                    if(CompilationBroker.SIMULATEADAPTER) {
                        /*
                        Needed as we now have adjusted the start address to include the adapter offset?
                        If this is not included then the stack adjustment and the LR register are lost!
                         */
                        masm.nop();
                        masm.nop();
                        masm.nop();
                        masm.nop();
                    }
		    /* FOR a valid stack backtrace?

		           //masm.mov(ConditionFlag.Always,false,ARMV7.r12,ARMV7.r13);
		           //masm.push(ConditionFlag.Always,1<<11|1<<12|1<<14|1<<15);// r11, r12(stack),r14 (LR),r15(PC)
	              //masm.add(ConditionFlag.Always,false,ARMV7.r11,ARMV7.r13,12,0);

	              REMEMBER: ARMV7CompilerStubEmitter (prologue)
	                        ARMV7Assembler (return --)
	                        and FrameMap.java see the inCallerFrame, where we would need to do an adjustment to the offset
	                        if we change what is pushed onto the stack, ie if we push more than just LR we need to adject the offset
	         */
                    // We only Push LR as that is what X86 does
                    // this means we can reuse all their
                    // stack unwinding code
		    masm.push(ConditionFlag.Always,1<<14);
		            //masm.decrementq(ARMV7.r13,frameSize);// DIRTY HACK TO get STACK to work
                    

		    //masm.push(ConditionFlag.Always,1<<11|1<<14);
		    masm.decrementq(ARMV7.r13, frameSize); // does not emit code for frameSize == 0

                    //masm.vmov(ConditionFlag.Always,ARMV7.s6,ARMV7.s3);
                   // masm.vmov(ConditionFlag.Always,ARMV7.s4,ARMV7.s2);
                   // masm.vmov(ConditionFlag.Always,ARMV7.s2,ARMV7.s1);

                    //masm.str(ConditionFlag.Always,ARMV7.r14,ARMV7.r13,0); // save the return value!!!!!

                    if (C1XOptions.ZapStackOnMethodEntry) {
                        final int intSize = 4;
                        for (int i = 0; i < frameSize / intSize; ++i) {
                            masm.setUpScratch(new CiAddress(CiKind.Int, ARMV7.r13.asValue(), i * intSize));
                            masm.mov32BitConstant(ARMV7.r8,0xC1C1C1C1);
                            masm.str(ConditionFlag.Always, 0, 0, 0, ARMV7.r8, ARMV7.r12, ARMV7.r0, 0, 0);
                       //     masm.movl(new CiAddress(CiKind.Int, ARMV7.rsp.asValue(), i * intSize), 0xC1C1C1C1);
                        }
                    }

                    CiCalleeSaveLayout csl = compilation.registerConfig.getCalleeSaveLayout();
                    if (csl != null && csl.size != 0) {
                        int frameToCSA = frameMap.offsetToCalleeSaveAreaStart();
                        assert frameToCSA >= 0;
                        masm.save(csl, frameToCSA);
                    }

		
                    if (DEBUG_METHODS) {
                        int a = methodCounter.incrementAndGet();
                        masm.mov32BitConstant(ARMV7.r12, a);
                        try {
                            writeDebugMethod(compilation.method.holder() +"." + compilation.method.name() , a);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    break;
                }
                case PopFrame: {
                    int frameSize = initialFrameSizeInBytes();

                    CiCalleeSaveLayout csl = compilation.registerConfig.getCalleeSaveLayout();
                    if (csl != null && csl.size != 0) {
                        registerRestoreEpilogueOffset = masm.codeBuffer.position();
                        // saved all registers, restore all registers
                        int frameToCSA = frameMap.offsetToCalleeSaveAreaStart();
                        masm.restore(csl, frameToCSA);
                    }
                    masm.incrementq(ARMV7.r13,frameSize);


                    break;
                }
                case Push: {
                    CiRegisterValue value = assureInRegister(operands[inst.x().index]);
                    if (value.asRegister().number < 16) {
                        masm.push(ConditionFlag.Always, 1 << value.asRegister().number);
                    } else {
                        masm.vpush(ConditionFlag.Always,value.asRegister(),value.asRegister());
                    }
                 /// masm.push(value.asRegister());
                    break;
                }
                case Pop: {
                    CiValue result = operands[inst.result.index];
                    if (result.isRegister()) {
                     //   masm.pop(result.asRegister());
                        if(result.asRegister().encoding < 16) {
                            masm.pop(ConditionFlag.Always,1<< result.asRegister().encoding);
                        } else {
                            masm.vpop(ConditionFlag.Always,result.asRegister(),result.asRegister());
                        }
                    } else {
                      //  masm.pop(rscratch1);
                        masm.pop(ConditionFlag.Always,1<<12);
                        moveOp(rscratch1.asValue(), result, result.kind, null, true);
                    }
                    break;
                }
                case Mark: {
                    XirMark xmark = (XirMark) inst.extra;
                    Mark[] references = new Mark[xmark.references.length];
                    for (int i = 0; i < references.length; i++) {
                        references[i] = marks.get(xmark.references[i]);
                        assert references[i] != null;
                    }
                    Mark mark = tasm.recordMark(xmark.id, references);
                    marks.put(xmark, mark);
                    break;
                }
                case Nop: {
                    for (int i = 0; i < (Integer) inst.extra; i++) {
                        masm.nop();
                    }
                    break;
                }
                case RawBytes: {
                    for (byte b : (byte[]) inst.extra) {
                        masm.codeBuffer.emitByte(b & 0xff);
                    }
                    break;
                }
                case ShouldNotReachHere: {
                    if (inst.extra == null) {
                        stop("should not reach here");
                    } else {
                        stop("should not reach here: " + inst.extra);
                    }
                    break;
                }
                default:
                    throw Util.unimplemented("XIR operation " + inst.op);
            }
        }
    }

    /**
     * @param offset the offset RSP at which to bang. Note that this offset is relative to RSP after RSP has been
     *            adjusted to allocated the frame for the method. It denotes an offset "down" the stack.
     *            For very large frames, this means that the offset may actually be negative (i.e. denoting
     *            a slot "up" the stack above RSP).
     */
    private void bangStackWithOffset(int offset) {
        masm.setUpScratch(new CiAddress(target.wordKind, ARMV7.RSP, -offset));
        masm.strImmediate(ConditionFlag.Always, 0, 0, 0, ARMV7.r0, ARMV7.r12, 0);
        // assuming rax is the return value register
        //  masm.movq(new CiAddress(target.wordKind, ARMV7.RSP, -offset), ARMV7.rax);
    }

    private CiRegisterValue assureInRegister(CiValue pointer) {
        if (pointer.isConstant()) {
            CiRegisterValue register = rscratch1.asValue(pointer.kind);
            moveOp(pointer, register, pointer.kind, null, false);
            return register;
        }

        assert pointer.isRegister() : "should be register, but is: " + pointer;
        return (CiRegisterValue) pointer;
    }

    private void emitXirCompare(XirInstruction inst, Condition condition, ConditionFlag cflag, CiValue[] ops, Label label) {
        CiValue x = ops[inst.x().index];
        CiValue y = ops[inst.y().index];
        emitCompare(condition, x, y, null);
        masm.jcc(cflag, label); // allow space movw, movt add pc, blx(ConditionFlag,r12)
        masm.nop(3); // allow space in buffer on patchJumpTarget +3instructions
    }

    @Override
    public void emitDeoptizationStub(DeoptimizationStub stub) {
        masm.bind(stub.label);
        directCall(CiRuntimeCall.Deoptimize, stub.info);
        shouldNotReachHere();
    }

    public CompilerStub lookupStub(XirTemplate template) {
        return compilation.compiler.lookupStub(template);
    }

    public void callStub(XirTemplate stub, LIRDebugInfo info, CiRegister result, CiValue... args) {
        if (DEBUG_MOVS) {
            masm.movw(ConditionFlag.Always, ARMV7.r12, 37);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 37);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 37);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 37);
            masm.movw(ConditionFlag.Always, ARMV7.r12, 37);
        }
        callStubHelper(lookupStub(stub), stub.resultOperand.kind, info, result, args);
    }

    public void callStub(CompilerStub stub, LIRDebugInfo info, CiRegister result, CiValue... args) {
        callStubHelper(stub, stub.resultKind, info, result, args);
    }

    private void callStubHelper(CompilerStub stub, CiKind resultKind, LIRDebugInfo info, CiRegister result, CiValue... args) {
        assert args.length == stub.inArgs.length;

        for (int i = 0; i < args.length; i++) {
            CiStackSlot inArg = stub.inArgs[i];
            assert inArg.inCallerFrame();
            CiStackSlot outArg = inArg.asOutArg();
            //System.out.print("CALL STUB STORE ");
            storeParameter(args[i], outArg);
        }

        directCall(stub.stubObject, info);

        if (result != CiRegister.None) {
            //System.out.print("CALL STUB RESULT ");

            final CiAddress src = compilation.frameMap().toStackAddress(stub.outResult.asOutArg());
            loadResult(result, src);
        }

        // Clear out parameters
        if (C1XOptions.GenAssertionCode) {
            for (int i = 0; i < args.length; i++) {
                CiStackSlot inArg = stub.inArgs[i];
                CiStackSlot outArg = inArg.asOutArg();
                CiAddress dst = compilation.frameMap().toStackAddress(outArg);
                masm.movptr(dst, 0);
            }
        }
    }

    private void loadResult(CiRegister dst, CiAddress src) {
        final CiKind kind = src.kind;
        masm.setUpScratch(src);
        if (kind == CiKind.Int || kind == CiKind.Boolean) {
            // masm.movl(dst, src);
            masm.ldrImmediate(ConditionFlag.Always, 1, 0, 0, dst, ARMV7.r12, 0);
        } else if (kind == CiKind.Float) {
            masm.vldr(ConditionFlag.Always, dst, ARMV7.r12, 0);
            // masm.movss(dst, src);
        } else if (kind == CiKind.Double) {
            masm.vldr(ConditionFlag.Always, dst, ARMV7.r12, 0);
            // masm.movsd(dst, src);
        } else if (kind == CiKind.Long) {
                masm.ldrd(ConditionFlag.Always, dst, ARMV7.r12, 0);
            if (DEBUG_MOVS) {
                masm.movw(ConditionFlag.Always, ARMV7.r12, 39);
                masm.movw(ConditionFlag.Always, ARMV7.r12, 39);
                masm.movw(ConditionFlag.Always, ARMV7.r12, 39);
                masm.movw(ConditionFlag.Always, ARMV7.r12, 39);
                masm.movw(ConditionFlag.Always, ARMV7.r12, 39);
            }
            // masm.movq(dst, src);
        } else { // Additional clause added by APN
            masm.ldrImmediate(ConditionFlag.Always, 1, 0, 0, dst, ARMV7.r12, 0);
        }
    }

    private void storeParameter(CiValue registerOrConstant, CiStackSlot outArg) {
        CiAddress dst = compilation.frameMap().toStackAddress(outArg);
        CiKind k = registerOrConstant.kind;

        if (registerOrConstant.isConstant()) {
            CiConstant c = (CiConstant) registerOrConstant;
            if (c.kind == CiKind.Object) {
                movoop(dst, c);
            } else {
                masm.movptr(dst, c.asInt());
            }
        } else if (registerOrConstant.isRegister()) {
            masm.setUpScratch(dst);
            if (k.isFloat()) {

                // TODO manipulate for use of s0..s31 registers
                masm.vstr(ConditionFlag.Always,asXmmFloatReg(registerOrConstant),ARMV7.r12,0);
                //masm.movss(dst, registerOrConstastoreParameternt.asRegister());
            } else if (k.isDouble()) {
                masm.vstr(ConditionFlag.Always,asXmmDoubleReg(registerOrConstant),ARMV7.r12,0);

                //   masm.movsd(dst, registerOrConstant.asRegister());
            } else if (k.isLong()) {
                masm.strd(ConditionFlag.Always,registerOrConstant.asRegister(),ARMV7.r12,0);
               // masm.movq(dst, registerOrConstant.asRegister());
            } else {
                masm.str(ConditionFlag.Always,registerOrConstant.asRegister(),ARMV7.r12,0);
            }
        } else {
            throw new InternalError("should not reach here");
        }
    }

    public void movoop(CiRegister dst, CiConstant obj) {
        assert obj.kind == CiKind.Object;
        if (obj.isNull()) {
            masm.xorq(ARMV7.r12, ARMV7.r12);
        } else {
            if (target.inlineObjects) {
		assert(0==1);
                masm.setUpScratch(tasm.recordDataReferenceInCode(obj));
		masm.mov32BitConstant(ARMV7.r12,0xdeaddead); // patched?

		masm.mov(ConditionFlag.Always,false,dst,ARMV7.r12);
             //   masm.movq(dst, 0xDEADDEADDEADDEADL);
            } else {
		masm.setUpScratch(tasm.recordDataReferenceInCode(obj));
		masm.addRegisters(ConditionFlag.Always,false,ARMV7.r12,ARMV7.r12,ARMV7.r15,0,0);
            	masm.ldr(ConditionFlag.Always,dst, ARMV7.r12, 0);


                //masm.strImmediate(ConditionFlag.Always,0,0,0,ARMV7.r12,dst,0);
             //   masm.movq(dst, 0xDEADDEADDEADDEADL);

             //   masm.movq(dst, tasm.recordDataReferenceInCode(obj));
            }//
        }
    }

    public void movoop(CiAddress dst, CiConstant obj) {
        movoop(ARMV7.r8, obj); // was rscratch1
        masm.setUpScratch(dst);
	masm.strImmediate(ConditionFlag.Always,0,0,0,ARMV7.r8,ARMV7.r12,0);

     //   masm.movq(dst, rscratch1);
    }

    public void directCall(Object target, LIRDebugInfo info) {
        int before = masm.codeBuffer.position();
        masm.call();
        int after = masm.codeBuffer.position();
        if (C1XOptions.EmitNopAfterCall) {
            masm.nop();
        }
        tasm.recordDirectCall(before, after - before, asCallTarget(target), info);
        tasm.recordExceptionHandlers(after, info);
        masm.nop(4);
    }

    public void directJmp(Object target) {
        int before = masm.codeBuffer.position();
        masm.jmp(0, true);
        int after = masm.codeBuffer.position();
        if (C1XOptions.EmitNopAfterCall) {
            masm.nop();
        }
        tasm.recordDirectCall(before, after - before, asCallTarget(target), null);
    }

    public void indirectCall(CiRegister src, Object target, LIRDebugInfo info) {
        int before = masm.codeBuffer.position();
        masm.call(src);
        int after = masm.codeBuffer.position();
        if (C1XOptions.EmitNopAfterCall) {
            masm.nop();
        }
        tasm.recordIndirectCall(before, after - before, asCallTarget(target), info);
        tasm.recordExceptionHandlers(after, info);
    }

    protected void stop(String msg) {
        if (C1XOptions.GenAssertionCode) {
            // TODO: pass a pointer to the message
            directCall(CiRuntimeCall.Debug, null);
            masm.hlt();
        }
    }

    public void shouldNotReachHere() {
        stop("should not reach here");
    }
}
