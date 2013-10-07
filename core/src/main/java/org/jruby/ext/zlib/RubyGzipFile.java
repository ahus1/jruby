/*
 */
package org.jruby.ext.zlib;

import java.util.ArrayList;
import java.util.List;
import org.jcodings.Encoding;
import org.joda.time.DateTime;
import org.jruby.CompatVersion;
import org.jruby.Ruby;
import org.jruby.RubyBoolean;
import org.jruby.RubyClass;
import org.jruby.RubyObject;
import org.jruby.RubyString;
import org.jruby.RubyTime;
import org.jruby.anno.JRubyClass;
import org.jruby.anno.JRubyMethod;
import org.jruby.runtime.Block;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;
import org.jruby.util.encoding.Transcoder;
import org.jruby.util.io.EncodingUtils;
import org.jruby.util.io.IOEncodable;

/**
 *
 */
@JRubyClass(name = "Zlib::GzipFile")
public class RubyGzipFile extends RubyObject implements IOEncodable {
    @JRubyClass(name = "Zlib::GzipFile::Error", parent = "Zlib::Error")
    public static class Error {}

    @JRubyClass(name = "Zlib::GzipFile::CRCError", parent = "Zlib::GzipFile::Error")
    public static class CRCError extends Error {}

    @JRubyClass(name = "Zlib::GzipFile::NoFooter", parent = "Zlib::GzipFile::Error")
    public static class NoFooter extends Error {}

    @JRubyClass(name = "Zlib::GzipFile::LengthError", parent = "Zlib::GzipFile::Error")
    public static class LengthError extends Error {}

    static IRubyObject wrapBlock(ThreadContext context, RubyGzipFile instance, Block block) {
        if (block.isGiven()) {
            try {
                return block.yield(context, instance);
            } finally {
                if (!instance.isClosed()) {
                    instance.close();
                }
            }
        }
        return instance;
    }

    static IRubyObject[] argsWithIo(IRubyObject io, IRubyObject[] args) {
        List<IRubyObject> newArgs = new ArrayList<IRubyObject>();
        newArgs.add(io);
        for (IRubyObject arg : args) {
            if (arg == null) {
                break;
            }
            newArgs.add(arg);
        }
        return newArgs.toArray(new IRubyObject[0]);
    }

    @JRubyMethod(meta = true, name = "wrap", compat = CompatVersion.RUBY1_8)
    public static IRubyObject wrap(ThreadContext context, IRubyObject recv, IRubyObject io, Block block) {
        Ruby runtime = recv.getRuntime();
        RubyGzipFile instance;

        // TODO: People extending GzipWriter/reader will break.  Find better way here.
        if (recv == runtime.getModule("Zlib").getClass("GzipWriter")) {
            instance = JZlibRubyGzipWriter.newInstance(recv, new IRubyObject[]{io}, block);
        } else {
            instance = JZlibRubyGzipReader.newInstance(recv, new IRubyObject[]{io}, block);
        }

        return wrapBlock(context, instance, block);
    }
    
    @JRubyMethod(meta = true, name = "wrap", required = 1, optional = 1, compat = CompatVersion.RUBY1_9)
    public static IRubyObject wrap19(ThreadContext context, IRubyObject recv, IRubyObject[] args, Block block) {
        Ruby runtime = recv.getRuntime();
        RubyGzipFile instance;

        // TODO: People extending GzipWriter/reader will break.  Find better way here.
        if (recv == runtime.getModule("Zlib").getClass("GzipWriter")) {
            instance = JZlibRubyGzipWriter.newInstance(recv, args, block);
        } else {
            instance = JZlibRubyGzipReader.newInstance(recv, args, block);
        }

        return wrapBlock(context, instance, block);
    }
    
    protected static final ObjectAllocator GZIPFILE_ALLOCATOR = new ObjectAllocator() {

        @Override
        public IRubyObject allocate(Ruby runtime, RubyClass klass) {
            return new RubyGzipFile(runtime, klass);
        }
    };

    @JRubyMethod(name = "new", meta = true)
    public static RubyGzipFile newInstance(IRubyObject recv, Block block) {
        RubyClass klass = (RubyClass) recv;

        RubyGzipFile result = (RubyGzipFile) klass.allocate();

        result.callInit(new IRubyObject[0], block);

        return result;
    }
    
    public RubyGzipFile(Ruby runtime, RubyClass type) {
        super(runtime, type);
        mtime = RubyTime.newTime(runtime, new DateTime());
        enc = null;
        enc2 = null;
    }
    
    // rb_gzfile_ecopts
    protected void ecopts(ThreadContext context, IRubyObject opts) {
        if (!opts.isNil()) {
            EncodingUtils.ioExtractEncodingOption(context, this, opts, null);
        }
        if (enc2 != null) {
            IRubyObject[] outOpts = new IRubyObject[]{opts};
            ecflags = EncodingUtils.econvPrepareOpts(context, opts, outOpts);
            ec = EncodingUtils.econvOpenOpts(context, enc.getName(), enc2.getName(), ecflags, opts);
            ecopts = opts;
        }
    }
    
    public Encoding getReadEncoding() {
        return enc == null ? getRuntime().getDefaultExternalEncoding() : enc;
    }
    
    public Encoding getEnc() {
        return enc;
    }
    
    public Encoding getInternalEncoding() {
        return enc2 == null ? getEnc() : enc2;
    }
    
    public Encoding getEnc2() {
        return enc2;
    }

    // c: gzfile_newstr
    protected RubyString newStr(Ruby runtime, ByteList value) {
        if (enc2 == null) {
            return RubyString.newString(runtime, value, getReadEncoding());
        }

        if (ec != null && enc2.isDummy()) {
            value = ec.convert(runtime.getCurrentContext(), value, false);
            return RubyString.newString(runtime, value, getEnc());
        }

        value = Transcoder.strConvEncOpts(runtime.getCurrentContext(), value, enc2, enc, ecflags, ecopts);
        return RubyString.newString(runtime, value);
    }

    @JRubyMethod(name = "os_code")
    public IRubyObject os_code() {
        return getRuntime().newFixnum(osCode & 0xff);
    }

    @JRubyMethod(name = "closed?")
    public IRubyObject closed_p() {
        return closed ? getRuntime().getTrue() : getRuntime().getFalse();
    }

    protected boolean isClosed() {
        return closed;
    }

    @JRubyMethod(name = "orig_name")
    public IRubyObject orig_name() {
        if (closed) {
            throw RubyZlib.newGzipFileError(getRuntime(), "closed gzip stream");
        }
        return nullFreeOrigName == null ? getRuntime().getNil() : nullFreeOrigName;
    }

    @JRubyMethod(name = "to_io")
    public IRubyObject to_io() {
        return realIo;
    }

    @JRubyMethod(name = "comment")
    public IRubyObject comment() {
        if (closed) {
            throw RubyZlib.newGzipFileError(getRuntime(), "closed gzip stream");
        }
        return nullFreeComment == null ? getRuntime().getNil() : nullFreeComment;
    }

    @JRubyMethod(name = "crc")
    public IRubyObject crc() {
        return getRuntime().newFixnum(0);
    }

    @JRubyMethod(name = "mtime")
    public IRubyObject mtime() {
        return mtime;
    }

    @JRubyMethod(name = "sync")
    public IRubyObject sync() {
        return sync ? getRuntime().getTrue() : getRuntime().getFalse();
    }

    @JRubyMethod(name = "finish")
    public IRubyObject finish() {
        if (!finished) {
            //io.finish();
        }
        finished = true;
        return realIo;
    }

    @JRubyMethod(name = "close")
    public IRubyObject close() {
        return null;
    }

    @JRubyMethod(name = "level")
    public IRubyObject level() {
        return getRuntime().newFixnum(level);
    }

    @JRubyMethod(name = "sync=", required = 1)
    public IRubyObject set_sync(IRubyObject arg) {
        sync = ((RubyBoolean) arg).isTrue();
        return sync ? getRuntime().getTrue() : getRuntime().getFalse();
    }
    
    @Override
    public void setEnc(Encoding readEncoding) {
        this.enc = readEncoding;
    }
    
    @Override
    public void setEnc2(Encoding writeEncoding) {
        this.enc2 = writeEncoding;
    }
    
    @Override
    public void setEcflags(int ecflags) {
        this.ecflags = ecflags;
    }
    
    @Override
    public int getEcflags() {
        return ecflags;
    }
    
    @Override
    public void setEcopts(IRubyObject ecopts) {
        this.ecopts = ecopts;
    }
    
    @Override
    public IRubyObject getEcopts() {
        return ecopts;
    }
    
    @Override
    public void setBOM(boolean bom) {
        this.hasBOM = bom;
    }
    
    @Override
    public boolean getBOM() {
        return hasBOM;
    }
    
    protected boolean closed = false;
    protected boolean finished = false;
    protected boolean hasBOM;
    protected byte osCode = Zlib.OS_UNKNOWN;
    protected int level = -1;
    protected RubyString nullFreeOrigName;
    protected RubyString nullFreeComment;
    protected IRubyObject realIo;
    protected RubyTime mtime;
    protected Encoding enc;
    protected Encoding enc2;
    protected int ecflags;
    protected IRubyObject ecopts;
    protected Transcoder ec;
    protected boolean sync = false;
    protected Transcoder readTranscoder = null;
    protected Transcoder writeTranscoder = null;    
}