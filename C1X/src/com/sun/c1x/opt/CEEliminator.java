/*
 * Copyright (c) 2009 Sun Microsystems, Inc.  All rights reserved.
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
package com.sun.c1x.opt;

import com.sun.c1x.*;
import com.sun.c1x.graph.*;
import com.sun.c1x.ir.*;
import com.sun.c1x.value.*;
import com.sun.c1x.value.FrameState.*;
import com.sun.cri.ci.*;

/**
 * This class implements conditional-expression elimination, which replaces some
 * simple branching constructs with conditional moves.
 *
 * @author Ben L. Titzer
 */
public class CEEliminator implements BlockClosure {

    final IR ir;
    final InstructionSubstituter subst;

    public CEEliminator(IR ir) {
        this.ir = ir;
        this.subst = new InstructionSubstituter(ir);
        ir.startBlock.iteratePreOrder(this);
        subst.finish();
    }

    void adjustExceptionEdges(BlockBegin block, BlockBegin sux) {
        int e = sux.numberOfExceptionHandlers();
        for (int i = 0; i < e; i++) {
            BlockBegin xhandler = sux.exceptionHandlerAt(i);
            block.addExceptionHandler(xhandler);

            assert xhandler.isPredecessor(sux) : "missing predecessor";
            if (sux.numberOfPreds() == 0) {
                // sux is disconnected from graph so disconnect from exception handlers
                xhandler.removePredecessor(sux);
            }
            if (!xhandler.isPredecessor(block)) {
                xhandler.addPredecessor(block);
            }
        }
    }

    public void apply(BlockBegin block) {
        // 1) check that block ends with an If
        if (!(block.end() instanceof If)) {
            return;
        }
        If curIf = (If) block.end();

        // check that the if's operands are of int or object type
        CiKind ifType = curIf.x().kind;
        if (!ifType.isInt() && !ifType.isObject()) {
            return;
        }

        BlockBegin tBlock = curIf.trueSuccessor();
        BlockBegin fBlock = curIf.falseSuccessor();
        Instruction tCur = tBlock.next();
        Instruction fCur = fBlock.next();

        // one Constant may be present between BlockBegin and BlockEnd
        Instruction tConst = null;
        Instruction fConst = null;
        if (tCur instanceof Constant) {
            tConst = tCur;
            tCur = tCur.next();
        }
        if (fCur instanceof Constant) {
            fConst = fCur;
            fCur = fCur.next();
        }

        // check that both branches end with a goto
        if (!(tCur instanceof Goto) || !(fCur instanceof Goto)) {
            return;
        }
        Goto tGoto = (Goto) tCur;
        Goto fGoto = (Goto) fCur;

        // check that both gotos merge into the same block
        BlockBegin sux = tGoto.defaultSuccessor();
        if (sux != fGoto.defaultSuccessor()) {
            return;
        }

        // check that at least one word was pushed on suxState
        FrameState suxState = sux.stateBefore();
        if (suxState.stackSize() <= curIf.stateAfter().stackSize()) {
            return;
        }

        // check that phi function is present at end of successor stack and that
        // only this phi was pushed on the stack
        final Value suxPhi = suxState.stackAt(curIf.stateAfter().stackSize());
        if (suxPhi == null || !(suxPhi instanceof Phi) || ((Phi) suxPhi).block() != sux) {
            return;
        }
        if (suxPhi.kind.sizeInSlots() != suxState.stackSize() - curIf.stateAfter().stackSize()) {
            return;
        }

        // get the values that were pushed in the true- and false-branch
        Value tValue = tGoto.stateAfter().stackAt(curIf.stateAfter().stackSize());
        Value fValue = fGoto.stateAfter().stackAt(curIf.stateAfter().stackSize());

        assert tValue.kind == fValue.kind : "incompatible types";

        if (tValue.kind.isFloat() || tValue.kind.isDouble()) {
            // backend does not support conditional moves on floats
            return;
        }

        // check that successor has no other phi functions but suxPhi
        // this can happen when tBlock or fBlock contained additional stores to local variables
        // that are no longer represented by explicit instructions
        boolean suxHasOtherPhi = sux.stateBefore().forEachPhi(sux, new PhiProcedure() {
            public boolean doPhi(Phi phi) {
                return phi == suxPhi;
            }
        });
        if (suxHasOtherPhi) {
            return;
        }

        // check that true and false blocks don't have phis
        if (tBlock.stateBefore().hasPhis() || fBlock.stateBefore().hasPhis()) {
            return;
        }

        // 2) cut off the original if and replace with constants and a Goto
        // cut curIf away and get node before
        Instruction ifPrev = curIf.prev(block);
        int bci = curIf.bci();

        // append constants of true- and false-block if necessary
        // clone constants because original block must not be destroyed
        assert (tValue != fConst && fValue != tConst) || tConst == fConst : "mismatch";
        if (tValue == tConst) {
            Constant tc = new Constant(tConst.asConstant());
            tValue = tc;
            ifPrev = ifPrev.setNext(tc, bci);
        }
        if (fValue == fConst) {
            Constant fc = new Constant(fConst.asConstant());
            fValue = fc;
            ifPrev = ifPrev.setNext(fc, bci);
        }

        Value result;
        if (tValue == fValue) {
            // conditional chooses the same value regardless
            result = tValue;
            C1XMetrics.RedundantConditionals++;
        } else {
            // it is very unlikely that the condition can be statically decided
            // (this was checked previously by the Canonicalizer), so always
            // append IfOp
            result = new IfOp(curIf.x(), curIf.condition(), curIf.y(), tValue, fValue);
            ifPrev = ifPrev.setNext((Instruction) result, bci);
        }

        // append Goto to successor
        FrameState stateBefore = curIf.isSafepoint() ? curIf.stateAfter() : null;
        Goto newGoto = new Goto(sux, stateBefore, curIf.isSafepoint() || tGoto.isSafepoint() || fGoto.isSafepoint());

        // prepare state for Goto
        FrameState tempGotoState = curIf.stateAfter();
        while (suxState.scope() != tempGotoState.scope()) {
            tempGotoState = tempGotoState.popScope();
            assert tempGotoState != null : "states do not match up";
        }
        MutableFrameState gotoState = tempGotoState.copy();
        gotoState.push(result.kind, result);
        assert gotoState.isSameAcrossScopes(suxState) : "states must match now";
        // ATTN: assumption: last use of gotoState, else add .immutableCopy()
        newGoto.setStateAfter(gotoState);

        // Steal the bci for the goto from the sux
        ifPrev = ifPrev.setNext(newGoto, sux.bci());

        // update block end (will remove this block from tBlock and fBlock predecessors)
        block.setEnd(newGoto);

        // remove blocks if they became unreachable
        tryRemove(tBlock);
        tryRemove(fBlock);

        // substitute the phi if possible
        Phi suxAsPhi = (Phi) suxPhi;
        if (suxAsPhi.inputCount() == 1) {
            // if the successor block now has only one predecessor
            assert suxAsPhi.inputAt(0) == result : "screwed up phi";
            subst.setSubst(suxPhi, result);

            // 3) successfully eliminated a conditional expression
            C1XMetrics.ConditionalEliminations++;
        }
    }

    private void tryRemove(BlockBegin succ) {
        if (succ.numberOfPreds() == 0) {
            // block just became unreachable
            for (BlockBegin s : succ.end().successors()) {
                s.predecessors().remove(succ);
            }
        }
    }
}
