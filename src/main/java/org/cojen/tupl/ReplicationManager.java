/*
 *  Copyright 2012-2013 Brian S O'Neill
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
 * Interface which replaces the redo log, for replicating transactional operations.
 * Non-transactional operations, or those with {@link DurabilityMode#NO_REDO NO_REDO}
 * durability will not pass through the replication manager.
 *
 * @author Brian S O'Neill
 * @see DatabaseConfig#replicate
 */
public interface ReplicationManager {
    /**
     * Start the replication manager in replica mode. Invocation of this method implies that
     * all data lower than the given position is confirmed. All data at or higher than the
     * given position might be discarded.
     *
     * <p>After started, the reported {@link #position position} must match the one provided to
     * this method. The position can change only after read and write operations have been
     * performed.
     *
     * @param position position to start reading from; 0 is the lowest position
     * @throws IllegalArgumentException if position is negative
     * @throws IllegalStateException if already started
     */
    void start(long position) throws IOException;

    /**
     * Called after replication threads have started, providing an opportunity to wait until
     * replication has sufficiently "caught up". The thread which is opening the database
     * invokes this method, and so it blocks until recovery completes.
     *
     * @param listener optional listener for posting recovery events to
     */
    void recover(EventListener listener) throws IOException;

    /**
     * Returns the next position a replica will read from. Position is never
     * negative and never retreats.
     */
    long readPosition();

    /**
     * Returns the next position a leader will write to. Valid only if local
     * instance is the leader.
     */
    long writePosition();

    /**
     * Indicates that all data prior to the given log position has been durably
     * checkpointed. The log can discard the old data. This method is never invoked
     * concurrently, and the implementation should return quickly.
     *
     * @param position log position immediately after the checkpoint position
     */
    //void checkpointed(long position) throws IOException;

    /**
     * Instruct that all data starting at the given position must be deleted.
     */
    //void truncate(long position) throws IOException;

    /**
     * Blocks at most once, reading as much replication input as possible. Returns -1 if local
     * instance has become the leader.
     *
     * @return amount read, or -1 if leader
     * @throws IllegalStateException if not started
     */
    int read(byte[] b, int off, int len) throws IOException;

    /**
     * Called to acknowledge mode change from replica to leader, or vice versa. Until flip is
     * called, all read and write operations fail as if the leadership mode is indeterminate.
     * Reads fail as if the local instance is the leader, and writes fail as if the local
     * instance is a replica.
     */
    void flip();

    /**
     * Fully writes the given data, returning false if local instance is not the leader.
     *
     * @return false if not leader
     */
    boolean write(byte[] b, int off, int len) throws IOException;

    /**
     * Commit all buffered writes and defines a confirmation position. When the local instance
     * loses leaderhsip, all data rolls back to the highest confirmed position.
     *
     * @return confirmation position, or -1 if not leader
     */
    long commit() throws IOException;

    /**
     * Blocks until all data up to the given log position is confirmed. Returns false if
     * local instance if not the leader or if operation timed out.
     *
     * @param timeoutNanos pass -1 for infinite
     * @return true if confirmed; false if not leader or timed out
     */
    boolean confirm(long position, long timeoutNanos) throws IOException;

    /**
     * Durably flushes all local data to non-volatile storage, up to the current position.
     */
    void sync() throws IOException;

    /**
     * Durably flushes all local data to non-volatile storage, up to the given
     * position, and then blocks until confirmed.
     *
     * @param timeoutNanos pass -1 for infinite
     */
    void syncConfirm(long position, long timeoutNanos) throws IOException;

    /**
     * Forward a change from a replica to the leader. Change must arrive back through the input
     * stream. This method can be invoked concurrently by multiple threads.
     *
     * @return false if local instance is not a replica or if no leader has
     * been established
     */
    //boolean forward(byte[] b, int off, int len) throws IOException;
}
