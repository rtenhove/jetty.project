//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;

import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import static org.eclipse.jetty.server.HttpInput.State.*;

/**
 * {@link HttpInput} provides an implementation of {@link ServletInputStream} for {@link HttpChannel}.
 * <p/>
 * Content may arrive in patterns such as [content(), content(), messageComplete()] so that this class
 * maintains two states: the content state that tells whether there is content to consume and the EOF
 * state that tells whether an EOF has arrived.
 * Only once the content has been consumed the content state is moved to the EOF state.
 */
public abstract class HttpInput extends ServletInputStream implements Runnable
{
    private final static Logger LOG = Log.getLogger(HttpInput.class);

    public static class Content extends Callback.Adapter
    {
        private final ByteBuffer _content;
        public Content(ByteBuffer content)
        {
            _content=content;
        }
        
        public ByteBuffer getContent()
        {
            return _content;
        }
        
        public boolean hasContent()
        {
            return _content.hasRemaining();
        }
        
        public int remaining()
        {
            return _content.remaining();
        }
        
    }
    
    private final byte[] _oneByteBuffer = new byte[1];
    private final Object _lock;
    private HttpChannelState _channelState;
    private ReadListener _listener;
    private Throwable _onError;
    private boolean _notReady;
    private State _contentState = STREAM;
    private State _eofState;
    private long _contentRead;

    protected HttpInput()
    {
        this(null);
    }

    protected HttpInput(Object lock)
    {
        _lock = lock == null ? this : lock;
    }

    public void init(HttpChannelState state)
    {
        synchronized (lock())
        {
            _channelState = state;
        }
    }

    public final Object lock()
    {
        return _lock;
    }

    public void recycle()
    {
        synchronized (lock())
        {
            _listener = null;
            _onError = null;
            _notReady = false;
            _contentState = STREAM;
            _eofState = null;
            _contentRead = 0;
        }
    }

    @Override
    public int available()
    {
        try
        {
            synchronized (lock())
            {
                Content item = getNextContent();
                return item == null ? 0 : remaining(item);
            }
        }
        catch (IOException e)
        {
            throw new RuntimeIOException(e);
        }
    }

    @Override
    public int read() throws IOException
    {
        int read = read(_oneByteBuffer, 0, 1);
        return read < 0 ? -1 : _oneByteBuffer[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        synchronized (lock())
        {
            Content item = getNextContent();
            if (item == null)
            {
                _contentState.waitForContent(this);
                item = getNextContent();
                if (item == null)
                    return _contentState.noContent();
            }
            int l = get(item, b, off, len);
            _contentRead += l;
            return l;
        }
    }

    /**
     * A convenience method to call nextContent and to check the return value, which if null then the
     * a check is made for EOF and the state changed accordingly.
     *
     * @return Content or null if none available.
     * @throws IOException
     * @see #nextContent()
     */
    protected Content getNextContent() throws IOException
    {
        Content content = nextContent();
        if (content == null)
        {
            synchronized (lock())
            {
                if (_eofState != null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} eof {}", this, _eofState);
                    _contentState = _eofState;
                }
            }
        }
        return content;
    }

    /**
     * Access the next content to be consumed from.   Returning the next item does not consume it
     * and it may be returned multiple times until it is consumed.
     * <p/>
     * Calls to {@link #get(Content, byte[], int, int)}
     * or {@link #consume(Content, int)} are required to consume data from the content.
     *
     * @return the content or null if none available.
     * @throws IOException if retrieving the content fails
     */
    protected abstract Content nextContent() throws IOException;

    /**
     * @param item the content
     * @return how many bytes remain in the given content
     */
    protected int remaining(Content item)
    {
        return item.remaining();
    }


    /**
     * Copies the given content into the given byte buffer.
     *
     * @param content   the content to copy from
     * @param buffer the buffer to copy into
     * @param offset the buffer offset to start copying from
     * @param length the space available in the buffer
     * @return the number of bytes actually copied
     */
    protected int get(Content content, byte[] buffer, int offset, int length)
    {
        int l = Math.min(content.remaining(), length);
        content.getContent().get(buffer, offset, l);
        return l;
    }

    /**
     * Consumes the given content.
     * Calls the content succeeded if all content consumed.
     *
     * @param content   the content to consume
     * @param length the number of bytes to consume
     */
    protected void consume(Content content, int length)
    {
        ByteBuffer buffer = content.getContent();
        buffer.position(buffer.position()+length);
        if (length>0 && !buffer.hasRemaining())
            content.succeeded();
    }

    /**
     * Blocks until some content or some end-of-file event arrives.
     *
     * @throws IOException if the wait is interrupted
     */
    protected abstract void blockForContent() throws IOException;

    /**
     * Adds some content to this input stream.
     *
     * @param content the content to add
     */
    public abstract void content(Content content);

    protected boolean onAsyncRead()
    {
        synchronized (lock())
        {
            if (_listener == null)
                return false;
        }
        _channelState.onReadPossible();
        return true;
    }

    public long getContentRead()
    {
        synchronized (lock())
        {
            return _contentRead;
        }
    }

    /**
     * This method should be called to signal that an EOF has been
     * detected before all the expected content arrived.
     * <p/>
     * Typically this will result in an EOFException being thrown
     * from a subsequent read rather than a -1 return.
     */
    public void earlyEOF()
    {
        synchronized (lock())
        {
            if (!isEOF())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} early EOF", this);
                _eofState = EARLY_EOF;
                if (_listener == null)
                    return;
            }
        }
        _channelState.onReadPossible();
    }

    public boolean isEarlyEOF()
    {
        synchronized (lock())
        {
            return _contentState == EARLY_EOF;
        }
    }
    
    /**
     * This method should be called to signal that all the expected
     * content arrived.
     */
    public void messageComplete()
    {
        synchronized (lock())
        {
            if (!isEOF())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} EOF", this);
                _eofState = EOF;
                if (_listener == null)
                    return;
            }
        }
        _channelState.onReadPossible();
    }

    public void consumeAll()
    {
        synchronized (lock())
        {
            try
            {
                while (!isFinished())
                {
                    Content item = getNextContent();
                    if (item == null)
                        _contentState.waitForContent(this);
                    else
                        consume(item, remaining(item));
                }
            }
            catch (IOException e)
            {
                LOG.debug(e);
            }
        }
    }

    public boolean isAsync()
    {
        synchronized (lock())
        {
            return _contentState == ASYNC;
        }
    }
    
    /**
     * @return whether an EOF has been detected, even though there may be content to consume.
     */
    public boolean isEOF()
    {
        synchronized (lock())
        {
            return _eofState != null && _eofState.isEOF();
        }
    }

    @Override
    public boolean isFinished()
    {
        synchronized (lock())
        {
            return _contentState.isEOF();
        }
    }
    

    @Override
    public boolean isReady()
    {
        boolean finished;
        synchronized (lock())
        {
            if (_contentState.isEOF())
                return true;
            if (_listener == null )
                return true;
            if (available() > 0)
                return true;
            if (_notReady)
                return false;
            _notReady = true;
            finished = isFinished();
        }
        if (finished)
            _channelState.onReadPossible();
        else
            unready();
        return false;
    }

    protected void unready()
    {
    }

    @Override
    public void setReadListener(ReadListener readListener)
    {
        readListener = Objects.requireNonNull(readListener);
        synchronized (lock())
        {
            if (_contentState != STREAM)
                throw new IllegalStateException("state=" + _contentState);
            _contentState = ASYNC;
            _listener = readListener;
            _notReady = true;
        }
        _channelState.onReadPossible();
    }

    public void failed(Throwable x)
    {
        synchronized (lock())
        {
            if (_onError != null)
                LOG.warn(x);
            else
                _onError = x;
        }
    }

    @Override
    public void run()
    {
        final Throwable error;
        final ReadListener listener;
        boolean available = false;
        final boolean eof;

        synchronized (lock())
        {
            if (!_notReady || _listener == null)
                return;

            error = _onError;
            listener = _listener;

            try
            {
                Content item = getNextContent();
                available = item != null && remaining(item) > 0;
            }
            catch (Exception e)
            {
                failed(e);
            }

            eof = !available && isFinished();
            _notReady = !available && !eof;
        }

        try
        {
            if (error != null)
                listener.onError(error);
            else if (available)
                listener.onDataAvailable();
            else if (eof)
                listener.onAllDataRead();
            else
                unready();
        }
        catch (Throwable e)
        {
            LOG.warn(e.toString());
            LOG.debug(e);
            listener.onError(e);
        }
    }

    protected enum State {
        STREAM()
        {
            @Override
            public void waitForContent(HttpInput input) throws IOException
            {
                input.blockForContent();
            }
        },
        ASYNC()
        {
            @Override
            public int noContent() throws IOException
            {
                return 0;
            }
        },
        EARLY_EOF()
        {
            @Override
            public int noContent() throws IOException
            {
                throw new EofException("Early EOF");
            }

            @Override
            public boolean isEOF()
            {
                return true;
            }
        },
        EOF()
        {
            @Override
            public boolean isEOF()
            {
                return true;
            }
        },
        ;

        public void waitForContent(HttpInput in) throws  IOException
        {
        }

        public int noContent() throws IOException
        {
            return -1;
        }

        public boolean isEOF()
        {
            return false;
        }
    }

}
