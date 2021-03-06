/*
 *  Copyright 2011-2015 Cojen.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.tupl;

import java.io.IOException;

/**
 * Short-lived object for capturing the state of a partially completed node split.
 *
 * @author Brian S O'Neill
 */
final class Split {
    final boolean mSplitRight;
    private final Node mSibling;

    // In many cases a copy of the key is not necessary; a simple reference to
    // the appropriate sub node works fine. This strategy assumes that the sub
    // node will not be compacted before the split is completed. For this
    // reason, Split is always constructed with a copied key.

    private byte[] mFullKey;
    private byte[] mActualKey;

    Split(boolean splitRight, Node sibling) {
        mSplitRight = splitRight;
        mSibling = sibling;
    }

    final void setKey(Split split) {
        mFullKey = split.mFullKey;
        mActualKey = split.mActualKey;
    }

    final void setKey(byte[] fullKey, byte[] actualKey) {
        mFullKey = fullKey;
        mActualKey = actualKey;
    }

    /**
     * @return null if key is not fragmented
     */
    final byte[] fragmentedKey() {
        return mFullKey == mActualKey ? null : mActualKey;
    }

    /**
     * Compares to the split key, returning <0 if given key is lower, 0 if
     * equal, >0 if greater.
     */
    final int compare(byte[] key) {
        return Utils.compareUnsigned(key, 0, key.length, mFullKey, 0, mFullKey.length);
    }

    /**
     * Allows a search to continue into a split node by selecting the original node or the
     * sibling. If the original node is returned, its shared lock is still held. If the
     * sibling is returned, it will have a shared latch held and the original node's latch
     * is released.
     *
     * @param node node which was split; shared latch must be held
     * @return original node or sibling
     */
    final Node selectNode(Node node, byte[] key) {
        Node sibling = mSibling;
        sibling.acquireShared();

        Node left, right;
        if (mSplitRight) {
            left = node;
            right = sibling;
        } else {
            left = sibling;
            right = node;
        }

        if (compare(key) < 0) {
            right.releaseShared();
            return left;
        } else {
            left.releaseShared();
            return right;
        }
    }

    /**
     * Performs a binary search against the split, returning the position
     * within the original node as if it had not split.
     */
    final int binarySearchLeaf(Node node, byte[] key) throws IOException {
        Node sibling = latchSibling();

        Node left, right;
        if (mSplitRight) {
            left = node;
            right = sibling;
        } else {
            left = sibling;
            right = node;
        }

        int searchPos;
        if (compare(key) < 0) {
            searchPos = left.binarySearch(key);
        } else {
            int highestPos = left.highestLeafPos();
            searchPos = right.binarySearch(key);
            if (searchPos < 0) {
                searchPos = searchPos - highestPos - 2;
            } else {
                searchPos = highestPos + 2 + searchPos;
            }
        }

        sibling.releaseShared();

        return searchPos;
    }

    /**
     * Returns the highest position within the original node as if it had not split.
     */
    final int highestPos(Node node) {
        int pos;
        Node sibling = latchSibling();
        if (node.isLeaf()) {
            pos = node.highestLeafPos() + 2 + sibling.highestLeafPos();
        } else {
            pos = node.highestInternalPos() + 2 + sibling.highestInternalPos();
        }
        sibling.releaseShared();
        return pos;
    }

    /**
     * @return sibling with shared latch held
     */
    final Node latchSibling() {
        Node sibling = mSibling;
        sibling.acquireShared();
        return sibling;
    }

    /**
     * @return sibling with exclusive latch held
     */
    final Node latchSiblingEx() {
        Node sibling = mSibling;
        sibling.acquireExclusive();
        return sibling;
    }

    /**
     * @param frame frame affected by split; exclusive latch for sibling must also be held
     */
    final void rebindFrame(CursorFrame frame, Node sibling) {
        int pos = frame.mNodePos;

        if (mSplitRight) {
            Node frameNode = frame.mNode;
            if (frameNode == null) {
                // Frame is being concurrently unbound.
                return;
            }

            int highestPos = frameNode.highestPos();

            if (pos >= 0) {
                if (pos <= highestPos) {
                    // Nothing to do.
                } else {
                    frame.rebind(sibling, pos - highestPos - 2);
                }
                return;
            }

            pos = ~pos;

            if (pos <= highestPos) {
                // Nothing to do.
                return;
            }

            if (pos == highestPos + 2) {
                byte[] key = frame.mNotFoundKey;
                if (key == null || compare(key) < 0) {
                    // Nothing to do.
                    return;
                }
            }

            frame.rebind(sibling, ~(pos - highestPos - 2));
        } else {
            int highestPos = sibling.highestPos();

            if (pos >= 0) {
                if (pos <= highestPos) {
                    frame.rebind(sibling, pos);
                } else {
                    frame.mNodePos = pos - highestPos - 2;
                }
                return;
            }

            pos = ~pos;

            if (pos <= highestPos) {
                frame.rebind(sibling, ~pos);
                return;
            }

            if (pos == highestPos + 2) {
                byte[] key = frame.mNotFoundKey;
                if (key == null) {
                    return;
                }
                if (compare(key) < 0) {
                    frame.rebind(sibling, ~pos);
                    return;
                }
            }

            frame.mNodePos = ~(pos - highestPos - 2);
        }
    }

    /**
     * @return length of entry generated by copySplitKeyToParent
     */
    final int splitKeyEncodedLength() {
        byte[] actualKey = mActualKey;
        if (actualKey == mFullKey) {
            return Node.calculateKeyLength(actualKey);
        } else {
            return 2 + actualKey.length;
        }
    }

    /**
     * @param dest destination page of parent internal node
     * @param destLoc location in destination page
     * @return updated destLoc
     */
    final int copySplitKeyToParent(final /*P*/ byte[] dest, final int destLoc) {
        byte[] actualKey = mActualKey;
        if (actualKey == mFullKey) {
            return Node.encodeNormalKey(actualKey, dest, destLoc);
        } else {
            return Node.encodeFragmentedKey(actualKey, dest, destLoc);
        }
    }
}
