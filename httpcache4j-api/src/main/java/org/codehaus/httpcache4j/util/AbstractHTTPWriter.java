package org.codehaus.httpcache4j.util;

import org.apache.commons.io.IOUtils;
import org.codehaus.httpcache4j.HTTPException;
import org.codehaus.httpcache4j.Header;
import org.codehaus.httpcache4j.Headers;
import org.codehaus.httpcache4j.payload.Payload;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;

/**
 * @author <a href="mailto:hamnis@codehaus.org">Erlend Hamnaberg</a>
 * @version $Revision: #5 $ $Date: 2008/09/15 $
 */
public abstract class AbstractHTTPWriter {

    protected void writeHeaders(PrintWriter writer, Headers headers) {
        StringBuilder builder = new StringBuilder();
        for (Header header : headers) {
            if (builder.length() > 0) {
                builder.append("\r\n");
            }
            builder.append(header);
        }
        println(writer, builder.toString());
    }

    protected void println(PrintWriter writer, String value) {
        writer.printf("%s\r\n", value);
    }

    protected void writeBody(PrintWriter writer, Payload payload) {
        writer.print("\r\n");
        InputStream stream = payload.getInputStream();
        try {
            IOUtils.copy(stream, writer);
            writer.print("\r\n");
        }
        catch (IOException e) {
            throw new HTTPException("Unable to write the body of the response", e);
        }
        finally {
            IOUtils.closeQuietly(stream);
        }
    }

}
