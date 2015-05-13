/*******************************************************************************
 * Copyright (c) 2015 EclipseSource and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    EclipseSource - initial API and implementation
 ******************************************************************************/
package com.eclipsesource.v8;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.eclipsesource.v8.utils.V8ObjectUtils;

public class V8MultiThreadTest {

    private V8           runtime          = null;
    private List<Object> mergeSortResults = new ArrayList<>();

    @Test
    public void testKillThread() throws InterruptedException {
        Thread t = new Thread(new Runnable() {

            @Override
            public void run() {
                runtime = V8.createV8Runtime();
                try {
                    runtime.executeVoidScript("while ( true ) {try {} catch(e){}} ");
                } catch (V8RuntimeException e) {
                }
                runtime.release();
            }
        });
        t.start();
        Thread.sleep(1000);
        runtime.terminateExecution();
        t.join();
        // Make sure the test ends and that we don't deadlock.
    }

    private static final String sortAlgorithm = ""
                                                      + "function merge(left, right){\n"
                                                      + "  var result  = [],\n"
                                                      + "  il      = 0,\n"
                                                      + "  ir      = 0;\n"
                                                      + "  while (il < left.length && ir < right.length){\n"
                                                      + "    if (left[il] < right[ir]){\n"
                                                      + "      result.push(left[il++]);\n"
                                                      + "    } else {\n"
                                                      + "      result.push(right[ir++]);\n"
                                                      + "    }\n"
                                                      + "  }\n"
                                                      + "  return result.concat(left.slice(il)).concat(right.slice(ir));\n"
                                                      + "};\n"
                                                      + "\n"
                                                      + "function sort(data) {\n"
                                                      + "  if ( data.length === 1 ) {\n"
                                                      + "    return [data[0]];\n"
                                                      + "  } else if (data.length === 2 ) {\n"
                                                      + "    if ( data[1] < data[0] ) {\n"
                                                      + "      return [data[1],data[0]];\n"
                                                      + "    } else {\n"
                                                      + "      return data;\n"
                                                      + "    }\n"
                                                      + "  }\n"
                                                      + "  var mid = Math.floor(data.length / 2);\n"
                                                      + "  var first = data.slice(0, mid);\n"
                                                      + "  var second = data.slice(mid);\n"
                                                      + "  return merge(_sort( first ), _sort( second ) );\n"
                                                      + "}\n";

    public class Sort implements JavaCallback {
        List<Object> result = null;

        @Override
        public Object invoke(final V8Object receiver, final V8Array parameters) {
            final List<Object> data = V8ObjectUtils.toList(parameters);

            Thread t = new Thread(new Runnable() {

                @Override
                public void run() {
                    V8 runtime = V8.createV8Runtime();
                    runtime.registerJavaMethod(new Sort(), "_sort");
                    runtime.executeVoidScript(sortAlgorithm);
                    V8Array parameters = V8ObjectUtils.toV8Array(runtime, data);
                    V8Array _result = runtime.executeArrayFunction("sort", parameters);
                    result = V8ObjectUtils.toList(_result);
                    _result.release();
                    parameters.release();
                    runtime.release();
                }
            });
            t.start();
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return V8ObjectUtils.toV8Array(parameters.getRutime(), result);
        }
    }

    @Test
    public void testMultiV8Threads() throws InterruptedException {

        final List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Thread t = new Thread(new Runnable() {

                @Override
                public void run() {
                    V8 v8 = V8.createV8Runtime();
                    v8.registerJavaMethod(new Sort(), "_sort");
                    v8.executeVoidScript(sortAlgorithm);
                    V8Array data = new V8Array(v8);
                    int max = 100;
                    for (int i = 0; i < max; i++) {
                        data.push(max - i);
                    }
                    V8Array parameters = new V8Array(v8).push(data);
                    V8Array result = v8.executeArrayFunction("sort", parameters);
                    synchronized (threads) {
                        mergeSortResults.add(V8ObjectUtils.toList(result));
                    }
                    result.release();
                    parameters.release();
                    data.release();
                    v8.release();
                }
            });
            threads.add(t);
        }
        for (Thread thread : threads) {
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(10, mergeSortResults.size());
        for (int i = 0; i < 10; i++) {
            assertSorted((List<Integer>) mergeSortResults.get(i));
        }
    }

    private void assertSorted(final List<Integer> result) {
        for (int i = 0; i < (result.size() - 1); i++) {
            assertTrue(result.get(i) < result.get(i + 1));
        }
    }
}
