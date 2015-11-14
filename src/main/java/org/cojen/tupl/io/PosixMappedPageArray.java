/*
 *  Copyright 2015 Brian S O'Neill
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

package org.cojen.tupl.io;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import java.util.EnumSet;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class PosixMappedPageArray extends MappedPageArray {
    private final int mFileDescriptor;

    private volatile boolean mEmpty;

    PosixMappedPageArray(int pageSize, long pageCount,
                         File file, EnumSet<OpenOption> options)
        throws IOException
    {
        super(pageSize, pageCount, options);

        // Create file (if necessary) and get proper length.

        long fileLen;
        int fd;

        JavaFileIO fio = new JavaFileIO(file, options, 1, false);
        try {
            fileLen = fio.length();
            mEmpty = fileLen == 0;
            fd = PosixFileIO.openFd(file, options);
        } finally {
            fio.close();
        }

        long mappingSize = pageSize * pageCount;

        if (fileLen < mappingSize) {
            if (options.contains(OpenOption.READ_ONLY)) {
                throw new IOException("File is too short: " + fileLen + " < " + mappingSize);
            }
            // Grow the file or else accessing the mapping will seg fault.
            try {
                PosixFileIO.ftruncateFd(fd, mappingSize);
            } catch (IOException e) {
                try {
                    PosixFileIO.closeFd(fd);
                } catch (IOException e2) {
                    e.addSuppressed(e2);
                }
                throw e;
            }
        }

        int prot = 1 | 2; // PROT_READ | PROT_WRITE
        int flags = 1; // MAP_SHARED

        long ptr;
        try {
            ptr = PosixFileIO.mmapFd(mappingSize, prot, flags, fd, 0);
        } catch (IOException e) {
            try {
                PosixFileIO.closeFd(fd);
            } catch (IOException e2) {
                e.addSuppressed(e2);
            }
            throw e;
        }

        mFileDescriptor = fd;

        setMappingPtr(ptr);
    }

    @Override
    public long getPageCount() {
        return mEmpty ? 0 : super.getPageCount();
    }

    @Override
    public void setPageCount(long count) {
        mEmpty = count == 0;
    }

    void doSync(long mappingPtr, boolean metadata) throws IOException {
        PosixFileIO.msyncAddr(mappingPtr, super.getPageCount() * pageSize());
        if (metadata) {
            PosixFileIO.fsyncFd(mFileDescriptor);
        }
    }

    void doSyncPage(long mappingPtr, long index) throws IOException {
        int pageSize = pageSize();
        PosixFileIO.msyncAddr(mappingPtr + index * pageSize, pageSize);
    }

    void doClose(long mappingPtr) throws IOException {
        PosixFileIO.munmap(mappingPtr, super.getPageCount() * pageSize());
        PosixFileIO.closeFd(mFileDescriptor);
    }
}