/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.source.tuner.pluto.iio;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Minimal binding for the libiio 0.x C library (0.25 is the target), using the Java foreign function API.  It covers
 * what a receive-only Pluto tuner needs: discovering contexts, opening a context by URI, reading and writing channel
 * attributes, and reading sample buffers.
 *
 * libiio isn't bundled.  It's loaded from the system library path (installed by the libiio Windows installer into
 * System32, or the libiio0 package on Linux), or from the file or directory named by the libiio.library.path system
 * property.  libiio 1.x has a different API and isn't supported.
 *
 * size_t, ssize_t and ptrdiff_t are bound as 64-bit values, so only 64-bit platforms are supported.
 */
public final class LibIio
{
    /**
     * System property naming the libiio library file, or the directory that contains it.
     */
    public static final String LIBRARY_PATH_PROPERTY = "libiio.library.path";
    private static final Linker LINKER = Linker.nativeLinker();
    private static final StructLayout CAPTURE_LAYOUT = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO = CAPTURE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("errno"));
    private static final Linker.Option CAPTURE_ERRNO = Linker.Option.captureCallState("errno");
    private static final Object LOAD_LOCK = new Object();
    private static LibIio sInstance;
    private static String sLoadError;

    private final String mLocation;
    private final MethodHandle mLibraryGetVersion;
    private final MethodHandle mStrerror;
    private final MethodHandle mCreateScanContext;
    private final MethodHandle mScanContextDestroy;
    private final MethodHandle mScanContextGetInfoList;
    private final MethodHandle mContextInfoListFree;
    private final MethodHandle mContextInfoGetDescription;
    private final MethodHandle mContextInfoGetUri;
    private final MethodHandle mCreateContextFromUri;
    private final MethodHandle mContextDestroy;
    private final MethodHandle mContextGetVersion;
    private final MethodHandle mContextGetName;
    private final MethodHandle mContextGetDescription;
    private final MethodHandle mContextGetAttrsCount;
    private final MethodHandle mContextGetAttr;
    private final MethodHandle mContextSetTimeout;
    private final MethodHandle mContextFindDevice;
    private final MethodHandle mDeviceFindChannel;
    private final MethodHandle mDeviceSetKernelBuffersCount;
    private final MethodHandle mDeviceCreateBuffer;
    private final MethodHandle mChannelAttrRead;
    private final MethodHandle mChannelAttrWrite;
    private final MethodHandle mChannelAttrReadLongLong;
    private final MethodHandle mChannelAttrWriteLongLong;
    private final MethodHandle mChannelEnable;
    private final MethodHandle mChannelDisable;
    private final MethodHandle mBufferDestroy;
    private final MethodHandle mBufferRefill;
    private final MethodHandle mBufferCancel;
    private final MethodHandle mBufferStart;
    private final MethodHandle mBufferEnd;
    private final MethodHandle mBufferStep;

    private LibIio(SymbolLookup lookup, String location) throws IioException
    {
        mLocation = location;

        //libiio 1.x replaced iio_create_context_from_uri() with iio_create_context()
        if(lookup.find("iio_create_context_from_uri").isEmpty())
        {
            throw new IioException("The libiio library at [" + location + "] isn't version 0.x - install libiio 0.25", 0);
        }

        mLibraryGetVersion = handle(lookup, "iio_library_get_version", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
        mStrerror = handle(lookup, "iio_strerror", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS, JAVA_LONG));
        mCreateScanContext = handle(lookup, "iio_create_scan_context", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT),
                CAPTURE_ERRNO);
        mScanContextDestroy = handle(lookup, "iio_scan_context_destroy", FunctionDescriptor.ofVoid(ADDRESS));
        mScanContextGetInfoList = handle(lookup, "iio_scan_context_get_info_list",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));
        mContextInfoListFree = handle(lookup, "iio_context_info_list_free", FunctionDescriptor.ofVoid(ADDRESS));
        mContextInfoGetDescription = handle(lookup, "iio_context_info_get_description",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        mContextInfoGetUri = handle(lookup, "iio_context_info_get_uri", FunctionDescriptor.of(ADDRESS, ADDRESS));
        mCreateContextFromUri = handle(lookup, "iio_create_context_from_uri", FunctionDescriptor.of(ADDRESS, ADDRESS),
                CAPTURE_ERRNO);
        mContextDestroy = handle(lookup, "iio_context_destroy", FunctionDescriptor.ofVoid(ADDRESS));
        mContextGetVersion = handle(lookup, "iio_context_get_version",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        mContextGetName = handle(lookup, "iio_context_get_name", FunctionDescriptor.of(ADDRESS, ADDRESS));
        mContextGetDescription = handle(lookup, "iio_context_get_description", FunctionDescriptor.of(ADDRESS, ADDRESS));
        mContextGetAttrsCount = handle(lookup, "iio_context_get_attrs_count", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        mContextGetAttr = handle(lookup, "iio_context_get_attr",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        mContextSetTimeout = handle(lookup, "iio_context_set_timeout", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        mContextFindDevice = handle(lookup, "iio_context_find_device", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        mDeviceFindChannel = handle(lookup, "iio_device_find_channel",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_BOOLEAN));
        mDeviceSetKernelBuffersCount = handle(lookup, "iio_device_set_kernel_buffers_count",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        mDeviceCreateBuffer = handle(lookup, "iio_device_create_buffer",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_BOOLEAN), CAPTURE_ERRNO);
        mChannelAttrRead = handle(lookup, "iio_channel_attr_read",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
        mChannelAttrWrite = handle(lookup, "iio_channel_attr_write",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS));
        mChannelAttrReadLongLong = handle(lookup, "iio_channel_attr_read_longlong",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        mChannelAttrWriteLongLong = handle(lookup, "iio_channel_attr_write_longlong",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
        mChannelEnable = handle(lookup, "iio_channel_enable", FunctionDescriptor.ofVoid(ADDRESS));
        mChannelDisable = handle(lookup, "iio_channel_disable", FunctionDescriptor.ofVoid(ADDRESS));
        mBufferDestroy = handle(lookup, "iio_buffer_destroy", FunctionDescriptor.ofVoid(ADDRESS));
        mBufferRefill = handle(lookup, "iio_buffer_refill", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        mBufferCancel = handle(lookup, "iio_buffer_cancel", FunctionDescriptor.ofVoid(ADDRESS));
        mBufferStart = handle(lookup, "iio_buffer_start", FunctionDescriptor.of(ADDRESS, ADDRESS));
        mBufferEnd = handle(lookup, "iio_buffer_end", FunctionDescriptor.of(ADDRESS, ADDRESS));
        mBufferStep = handle(lookup, "iio_buffer_step", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    }

    /**
     * Loads libiio the first time it's called and returns the binding.  A failed load is remembered, so later calls
     * fail with the same message without trying again.
     * @return binding
     * @throws IioException if libiio can't be loaded
     */
    public static LibIio get() throws IioException
    {
        synchronized(LOAD_LOCK)
        {
            if(sInstance == null && sLoadError == null)
            {
                try
                {
                    sInstance = load();
                }
                catch(IioException e)
                {
                    sLoadError = e.getMessage();
                }
                catch(RuntimeException | LinkageError e)
                {
                    sLoadError = "Can't load libiio - " + e.getMessage();
                }
            }

            if(sInstance == null)
            {
                throw new IioException(sLoadError, 0);
            }

            return sInstance;
        }
    }

    /**
     * Loads the library from the libiio.library.path location, or the system library path.
     */
    private static LibIio load() throws IioException
    {
        Arena arena = Arena.global();
        String override = System.getProperty(LIBRARY_PATH_PROPERTY);

        if(override != null && !override.isBlank())
        {
            Path path = Path.of(override.trim());
            Path file = Files.isDirectory(path) ? path.resolve(getLibraryNames().getFirst()) : path;

            if(isWindows() && file.getParent() != null)
            {
                preloadWindowsDependencies(file.getParent(), file, arena);
            }

            try
            {
                return new LibIio(SymbolLookup.libraryLookup(file, arena), file.toString());
            }
            catch(IllegalArgumentException e)
            {
                throw new IioException("Can't load libiio from [" + file + "] (" + LIBRARY_PATH_PROPERTY + ") - " +
                        e.getMessage(), 0);
            }
        }

        for(String name: getLibraryNames())
        {
            SymbolLookup lookup;

            try
            {
                lookup = SymbolLookup.libraryLookup(name, arena);
            }
            catch(IllegalArgumentException e)
            {
                continue;
            }

            return new LibIio(lookup, name);
        }

        throw new IioException("libiio isn't installed - install libiio 0.25 from " +
                "https://github.com/analogdevicesinc/libiio/releases/tag/v0.25 or start sdrtrunk with -D" +
                LIBRARY_PATH_PROPERTY + "=<libiio file or directory>", 0);
    }

    /**
     * Library names to try, in order, for this operating system.
     */
    static List<String> getLibraryNames()
    {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if(os.contains("win"))
        {
            return List.of("libiio.dll");
        }
        else if(os.contains("mac"))
        {
            return List.of("libiio.dylib", "/Library/Frameworks/iio.framework/iio", "/opt/homebrew/lib/libiio.dylib",
                    "/usr/local/lib/libiio.dylib", "libiio.0.dylib");
        }

        return List.of("libiio.so.0", "libiio.so");
    }

    private static boolean isWindows()
    {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Loads the other DLLs in the libiio directory (libxml2, libusb, the C runtime) before libiio itself.  Windows
     * doesn't search the directory of a DLL loaded by path for its dependencies, but it resolves them to DLLs that are
     * already loaded.  The DLLs depend on each other, so they're loaded in passes until no more can be loaded.
     * @param directory containing libiio
     * @param library file to skip, since it's loaded afterward
     * @param arena for the libraries
     */
    private static void preloadWindowsDependencies(Path directory, Path library, Arena arena)
    {
        List<Path> pending = new ArrayList<>();

        try(Stream<Path> files = Files.list(directory))
        {
            files.filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dll"))
                    .filter(file -> !file.getFileName().equals(library.getFileName()))
                    .forEach(pending::add);
        }
        catch(IOException e)
        {
            return;
        }

        boolean loaded = true;

        while(loaded && !pending.isEmpty())
        {
            loaded = false;

            for(Path file: List.copyOf(pending))
            {
                try
                {
                    SymbolLookup.libraryLookup(file, arena);
                    pending.remove(file);
                    loaded = true;
                }
                catch(IllegalArgumentException e)
                {
                    //A dependency isn't loaded yet - try again in the next pass
                }
            }
        }
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor descriptor,
                                       Linker.Option... options) throws IioException
    {
        MemorySegment symbol = lookup.find(name).orElseThrow(() -> new IioException("libiio doesn't have function [" +
                name + "] - install libiio 0.25", 0));
        return LINKER.downcallHandle(symbol, descriptor, options);
    }

    /**
     * Location that the library was loaded from: a file path or a library name.
     */
    public String location()
    {
        return mLocation;
    }

    /**
     * Library version, such as 0.25 (b6028fd).
     */
    public String version()
    {
        try(Arena arena = Arena.ofConfined())
        {
            MemorySegment major = arena.allocate(JAVA_INT);
            MemorySegment minor = arena.allocate(JAVA_INT);
            MemorySegment tag = arena.allocate(8);
            mLibraryGetVersion.invokeExact(major, minor, tag);
            return major.get(JAVA_INT, 0) + "." + minor.get(JAVA_INT, 0) + " (" + tag.getString(0).trim() + ")";
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    /**
     * Description of an errno value from libiio, such as: Broken pipe (32)
     * @param code positive errno value
     */
    static String strerror(int code)
    {
        //The Windows C library doesn't describe the Windows socket error codes that the network backend returns
        String socketError = switch(code)
        {
            case 10053 -> "Connection aborted";
            case 10054 -> "Connection reset";
            case 10060 -> "Connection timed out";
            case 10061 -> "Connection refused";
            case 10064 -> "Host is down";
            case 10065 -> "No route to host";
            default -> null;
        };

        if(socketError != null)
        {
            return socketError + " (" + code + ")";
        }

        LibIio lib;

        synchronized(LOAD_LOCK)
        {
            lib = sInstance;
        }

        if(lib != null)
        {
            try(Arena arena = Arena.ofConfined())
            {
                MemorySegment text = arena.allocate(256);
                lib.mStrerror.invokeExact(code, text, 256L);
                return text.getString(0);
            }
            catch(Throwable t)
            {
                //Fall through to the generic description
            }
        }

        return "error (" + code + ")";
    }

    /**
     * Discovers the contexts available through the backends.
     * @param backends to scan, such as usb, or usb=0456:b673 to scan only for devices with that USB vendor and product
     * @return discovered contexts
     * @throws IioException if the scan fails
     */
    public List<IioContextInfo> scan(String backends) throws IioException
    {
        try(Arena arena = Arena.ofConfined())
        {
            MemorySegment capture = arena.allocate(CAPTURE_LAYOUT);
            MemorySegment scan = (MemorySegment)mCreateScanContext.invokeExact(capture, arena.allocateFrom(backends), 0);

            if(scan.equals(MemorySegment.NULL))
            {
                throw new IioException("Can't create libiio scan context for [" + backends + "]", errno(capture));
            }

            try
            {
                MemorySegment listPointer = arena.allocate(ADDRESS);
                long count = (long)mScanContextGetInfoList.invokeExact(scan, listPointer);

                if(count < 0)
                {
                    throw new IioException("libiio scan for [" + backends + "] failed", (int)-count);
                }

                MemorySegment list = listPointer.get(ADDRESS, 0).reinterpret(count * ADDRESS.byteSize());
                List<IioContextInfo> contexts = new ArrayList<>();

                try
                {
                    for(int x = 0; x < count; x++)
                    {
                        MemorySegment info = list.getAtIndex(ADDRESS, x);
                        contexts.add(new IioContextInfo(string((MemorySegment)mContextInfoGetUri.invokeExact(info)),
                                string((MemorySegment)mContextInfoGetDescription.invokeExact(info))));
                    }
                }
                finally
                {
                    mContextInfoListFree.invokeExact(listPointer.get(ADDRESS, 0));
                }

                return contexts;
            }
            finally
            {
                mScanContextDestroy.invokeExact(scan);
            }
        }
        catch(IioException e)
        {
            throw e;
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    /**
     * Opens a context.
     * @param uri such as usb:1.5.5, ip:192.168.2.1 or ip:pluto.local
     * @return context, which must be closed
     * @throws IioException if the context can't be opened
     */
    public IioContext open(String uri) throws IioException
    {
        try(Arena arena = Arena.ofConfined())
        {
            MemorySegment capture = arena.allocate(CAPTURE_LAYOUT);
            MemorySegment context = (MemorySegment)mCreateContextFromUri.invokeExact(capture, arena.allocateFrom(uri));

            if(context.equals(MemorySegment.NULL))
            {
                throw new IioException("Can't open libiio context [" + uri + "]", errno(capture));
            }

            return new IioContext(this, context, uri);
        }
        catch(IioException e)
        {
            throw e;
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    void contextDestroy(MemorySegment context)
    {
        try
        {
            mContextDestroy.invokeExact(context);
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    /**
     * Version of the remote end of the context (iiod) as major.minor (git tag), or of the library for a local context.
     */
    String contextVersion(MemorySegment context) throws IioException
    {
        try(Arena arena = Arena.ofConfined())
        {
            MemorySegment major = arena.allocate(JAVA_INT);
            MemorySegment minor = arena.allocate(JAVA_INT);
            MemorySegment tag = arena.allocate(8);
            int result = (int)mContextGetVersion.invokeExact(context, major, minor, tag);
            check(result, "Can't read libiio context version");
            return major.get(JAVA_INT, 0) + "." + minor.get(JAVA_INT, 0) + " (" + tag.getString(0).trim() + ")";
        }
        catch(IioException e)
        {
            throw e;
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    String contextName(MemorySegment context)
    {
        try
        {
            return string((MemorySegment)mContextGetName.invokeExact(context));
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    String contextDescription(MemorySegment context)
    {
        try
        {
            return string((MemorySegment)mContextGetDescription.invokeExact(context));
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    Map<String,String> contextAttributes(MemorySegment context)
    {
        Map<String,String> attributes = new LinkedHashMap<>();

        try(Arena arena = Arena.ofConfined())
        {
            int count = (int)mContextGetAttrsCount.invokeExact(context);
            MemorySegment name = arena.allocate(ADDRESS);
            MemorySegment value = arena.allocate(ADDRESS);

            for(int x = 0; x < count; x++)
            {
                if((int)mContextGetAttr.invokeExact(context, x, name, value) == 0)
                {
                    attributes.put(string(name.get(ADDRESS, 0)), string(value.get(ADDRESS, 0)));
                }
            }
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }

        return attributes;
    }

    void contextSetTimeout(MemorySegment context, int milliseconds) throws IioException
    {
        try
        {
            check((int)mContextSetTimeout.invokeExact(context, milliseconds), "Can't set libiio context timeout");
        }
        catch(IioException e)
        {
            throw e;
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    MemorySegment contextFindDevice(MemorySegment context, String name)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return (MemorySegment)mContextFindDevice.invokeExact(context, arena.allocateFrom(name));
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    MemorySegment deviceFindChannel(MemorySegment device, String name, boolean output)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return (MemorySegment)mDeviceFindChannel.invokeExact(device, arena.allocateFrom(name), output);
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    void deviceSetKernelBuffersCount(MemorySegment device, int count) throws IioException
    {
        try
        {
            check((int)mDeviceSetKernelBuffersCount.invokeExact(device, count), "Can't set kernel buffer count");
        }
        catch(IioException e)
        {
            throw e;
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    MemorySegment deviceCreateBuffer(MemorySegment device, long samples) throws IioException
    {
        try(Arena arena = Arena.ofConfined())
        {
            MemorySegment capture = arena.allocate(CAPTURE_LAYOUT);
            MemorySegment buffer = (MemorySegment)mDeviceCreateBuffer.invokeExact(capture, device, samples, false);

            if(buffer.equals(MemorySegment.NULL))
            {
                throw new IioException("Can't create libiio buffer of [" + samples + "] samples", errno(capture));
            }

            return buffer;
        }
        catch(IioException e)
        {
            throw e;
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    String channelAttrRead(MemorySegment channel, String attribute) throws IioException
    {
        try(Arena arena = Arena.ofConfined())
        {
            int size = 4096;
            MemorySegment text = arena.allocate(size);
            long result = (long)mChannelAttrRead.invokeExact(channel, arena.allocateFrom(attribute), text, (long)size);
            check(result, "Can't read attribute [" + attribute + "]");
            return text.getString(0).trim();
        }
        catch(IioException e)
        {
            throw e;
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    void channelAttrWrite(MemorySegment channel, String attribute, String value) throws IioException
    {
        try(Arena arena = Arena.ofConfined())
        {
            long result = (long)mChannelAttrWrite.invokeExact(channel, arena.allocateFrom(attribute),
                    arena.allocateFrom(value));
            check(result, "Can't write [" + value + "] to attribute [" + attribute + "]");
        }
        catch(IioException e)
        {
            throw e;
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    long channelAttrReadLong(MemorySegment channel, String attribute) throws IioException
    {
        try(Arena arena = Arena.ofConfined())
        {
            MemorySegment value = arena.allocate(JAVA_LONG);
            int result = (int)mChannelAttrReadLongLong.invokeExact(channel, arena.allocateFrom(attribute), value);
            check(result, "Can't read attribute [" + attribute + "]");
            return value.get(JAVA_LONG, 0);
        }
        catch(IioException e)
        {
            throw e;
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    void channelAttrWriteLong(MemorySegment channel, String attribute, long value) throws IioException
    {
        try(Arena arena = Arena.ofConfined())
        {
            int result = (int)mChannelAttrWriteLongLong.invokeExact(channel, arena.allocateFrom(attribute), value);
            check(result, "Can't write [" + value + "] to attribute [" + attribute + "]");
        }
        catch(IioException e)
        {
            throw e;
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    void channelEnable(MemorySegment channel, boolean enable)
    {
        try
        {
            if(enable)
            {
                mChannelEnable.invokeExact(channel);
            }
            else
            {
                mChannelDisable.invokeExact(channel);
            }
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    void bufferDestroy(MemorySegment buffer)
    {
        try
        {
            mBufferDestroy.invokeExact(buffer);
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    long bufferRefill(MemorySegment buffer)
    {
        try
        {
            return (long)mBufferRefill.invokeExact(buffer);
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    void bufferCancel(MemorySegment buffer)
    {
        try
        {
            mBufferCancel.invokeExact(buffer);
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    MemorySegment bufferStart(MemorySegment buffer)
    {
        try
        {
            return (MemorySegment)mBufferStart.invokeExact(buffer);
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    MemorySegment bufferEnd(MemorySegment buffer)
    {
        try
        {
            return (MemorySegment)mBufferEnd.invokeExact(buffer);
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    long bufferStep(MemorySegment buffer)
    {
        try
        {
            return (long)mBufferStep.invokeExact(buffer);
        }
        catch(Throwable t)
        {
            throw rethrow(t);
        }
    }

    /**
     * Throws an exception for a negative errno result.
     */
    private static void check(long result, String message) throws IioException
    {
        if(result < 0)
        {
            throw new IioException(message, (int)-result);
        }
    }

    private static int errno(MemorySegment capture)
    {
        return (int)ERRNO.get(capture, 0L);
    }

    /**
     * Reads a C string, or returns null for a null pointer.
     */
    private static String string(MemorySegment pointer)
    {
        if(pointer.equals(MemorySegment.NULL))
        {
            return null;
        }

        return pointer.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /**
     * Rethrows an error from a native call.  Downcalls only throw errors from the Java runtime.
     */
    private static RuntimeException rethrow(Throwable t)
    {
        if(t instanceof RuntimeException runtimeException)
        {
            return runtimeException;
        }

        if(t instanceof Error error)
        {
            throw error;
        }

        return new IllegalStateException("Unexpected error calling libiio", t);
    }
}
