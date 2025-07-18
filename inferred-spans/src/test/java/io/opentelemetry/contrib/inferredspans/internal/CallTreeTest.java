/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.contrib.inferredspans.internal;

import static io.opentelemetry.contrib.inferredspans.internal.semconv.Attributes.LINK_IS_CHILD;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;
import static java.util.stream.Collectors.toMap;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.contrib.inferredspans.ProfilerTestSetup;
import io.opentelemetry.contrib.inferredspans.internal.pooling.ObjectPool;
import io.opentelemetry.contrib.inferredspans.internal.util.DisabledOnOpenJ9;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.CodeAttributes;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

@DisabledOnOs(OS.WINDOWS)
@DisabledOnOpenJ9
class CallTreeTest {

  private ProfilerTestSetup profilerSetup;

  private FixedClock nanoClock;

  @BeforeEach
  void setUp() {
    nanoClock = new FixedClock();
    // disable scheduled profiling to not interfere with this test
    profilerSetup =
        ProfilerTestSetup.create(config -> config.clock(nanoClock).startScheduledProfiling(false));
    profilerSetup.profiler.setProfilingSessionOngoing(true);
  }

  @AfterEach
  void tearDown() throws IOException {
    profilerSetup.close();
  }

  @Test
  void testCallTree() {
    TraceContext traceContext = new TraceContext();
    CallTree.Root root =
        CallTree.createRoot(
            ObjectPool.createRecyclable(100, CallTree.Root::new), traceContext.serialize(), 0);
    ObjectPool<CallTree> callTreePool = ObjectPool.createRecyclable(100, CallTree::new);
    root.addStackTrace(Arrays.asList(StackFrame.of("A", "a")), 0, callTreePool, 0);
    root.addStackTrace(
        Arrays.asList(StackFrame.of("A", "b"), StackFrame.of("A", "a")),
        TimeUnit.MILLISECONDS.toNanos(10),
        callTreePool,
        0);
    root.addStackTrace(
        Arrays.asList(StackFrame.of("A", "b"), StackFrame.of("A", "a")),
        TimeUnit.MILLISECONDS.toNanos(20),
        callTreePool,
        0);
    root.addStackTrace(
        Arrays.asList(StackFrame.of("A", "a")), TimeUnit.MILLISECONDS.toNanos(30), callTreePool, 0);
    root.end(callTreePool, 0);

    assertThat(root.getCount()).isEqualTo(4);
    assertThat(root.getDepth()).isEqualTo(0);
    assertThat(root.getChildren()).hasSize(1);

    CallTree a = root.getLastChild();
    assertThat(a).isNotNull();
    assertThat(a.getFrame().getMethodName()).isEqualTo("a");
    assertThat(a.getCount()).isEqualTo(4);
    assertThat(a.getChildren()).hasSize(1);
    assertThat(a.getDepth()).isEqualTo(1);
    assertThat(a.isSuccessor(root)).isTrue();

    CallTree b = a.getLastChild();
    assertThat(b).isNotNull();
    assertThat(b.getFrame().getMethodName()).isEqualTo("b");
    assertThat(b.getCount()).isEqualTo(2);
    assertThat(b.getChildren()).isEmpty();
    assertThat(b.getDepth()).isEqualTo(2);
    assertThat(b.isSuccessor(a)).isTrue();
    assertThat(b.isSuccessor(root)).isTrue();
  }

  @Test
  void testGiveEmptyChildIdsTo() {
    CallTree rich = new CallTree();
    rich.addChildId(42, 0L);
    CallTree robinHood = new CallTree();
    CallTree poor = new CallTree();

    rich.giveLastChildIdTo(robinHood);
    robinHood.giveLastChildIdTo(poor);
    // list is not null but empty, expecting no exception
    robinHood.giveLastChildIdTo(rich);

    assertThat(rich.hasChildIds()).isFalse();
    assertThat(robinHood.hasChildIds()).isFalse();
    assertThat(poor.hasChildIds()).isTrue();
  }

  @Test
  void testTwoDistinctInvocationsOfMethodBShouldNotBeFoldedIntoOne() throws Exception {
    assertCallTree(
        new String[] {" bb bb", "aaaaaa"},
        new Object[][] {
          {"a", 6},
          {"  b", 2},
          {"  b", 2}
        });
  }

  @Test
  void testBasicCallTree() throws Exception {
    assertCallTree(
        new String[] {" cc ", " bbb", "aaaa"},
        new Object[][] {
          {"a", 4},
          {"  b", 3},
          {"    c", 2}
        },
        new Object[][] {
          {"a", 3},
          {"  b", 2},
          {"    c", 1}
        });
  }

  @Test
  void testShouldNotCreateInferredSpansForPillarsAndLeafShouldHaveStacktrace() throws Exception {
    assertCallTree(
        new String[] {" dd ", " cc ", " bb ", "aaaa"},
        new Object[][] {
          {"a", 4},
          {"  b", 2},
          {"    c", 2},
          {"      d", 2}
        },
        new Object[][] {
          {"a", 3},
          {"  d", 1, Arrays.asList("c", "b")}
        });
  }

  @Test
  void testRemoveNodesWithCountOne() throws Exception {
    assertCallTree(
        new String[] {" b ", "aaa"}, new Object[][] {{"a", 3}}, new Object[][] {{"a", 2}});
  }

  @Test
  void testSameTopOfStackDifferentBottom() throws Exception {
    assertCallTree(
        new String[] {"cccc", "aabb"},
        new Object[][] {
          {"a", 2},
          {"  c", 2},
          {"b", 2},
          {"  c", 2},
        });
  }

  @Test
  void testStackTraceWithRecursion() throws Exception {
    assertCallTree(
        new String[] {"bbccbbcc", "bbbbbbbb", "aaaaaaaa"},
        new Object[][] {
          {"a", 8},
          {"  b", 8},
          {"    b", 2},
          {"    c", 2},
          {"    b", 2},
          {"    c", 2},
        });
  }

  @Test
  void testFirstInferredSpanShouldHaveNoStackTrace() throws Exception {
    assertCallTree(
        new String[] {"bb", "aa"},
        new Object[][] {
          {"a", 2},
          {"  b", 2},
        },
        new Object[][] {
          {"b", 1},
        });
  }

  @Test
  void testCallTreeWithSpanActivations() throws Exception {
    assertCallTree(
        new String[] {"    cc ee   ", "   bbb dd   ", " a aaaaaa a ", "1 2      2 1"},
        new Object[][] {
          {"a", 8},
          {"  b", 3},
          {"    c", 2},
          {"  d", 2},
          {"    e", 2},
        },
        new Object[][] {
          {"1", 11},
          {"  a", 9},
          {"    2", 7},
          {"      b", 2},
          {"        c", 1},
          {"      e", 1, Arrays.asList("d")},
        });
  }

  /*
   * [1        ]    [1        ]
   *  [a      ]      [a      ]
   *   [2   ]    ─┐   [b     ]
   *    [b    ]   │   [c    ]
   *    [c   ]    └►  [2   ]
   *    []             []
   */
  @Test
  void testDeactivationBeforeEnd() throws Exception {
    assertCallTree(
        new String[] {
          "   dd      ",
          "   cccc c  ",
          "   bbbb bb ", // <- deactivation for span 2 happens before b and c ends
          " a aaaa aa ", //    that means b and c must have started before 2 has been activated
          "1 2    2  1" //    but we saw the first stack trace of b only after the activation of 2
        },
        new Object[][] {
          {"a", 7},
          {"  b", 6},
          {"    c", 5},
          {"      d", 2},
        },
        new Object[][] {
          {"1", 10},
          {"  a", 8},
          {"    b", 7},
          {"      c", 6},
          {"        2", 5},
          {"          d", 1},
        });
  }

  /*
   * [1           ]    [1           ]
   *  [a         ]      [a         ]
   *   [2   ] [3]        [b    ][3]   <- b is supposed to stealChildIdsFom(a)
   *    [b   ]           [2   ]          however, it should only steal 2, not 3
   */
  @Test
  void testDectivationBeforeEnd2() throws Exception {
    assertCallTree(
        new String[] {"   bbbb b     ", " a aaaa a a a ", "1 2    2 3 3 1"},
        new Object[][] {
          {"a", 8},
          {"  b", 5},
        },
        new Object[][] {
          {"1", 13},
          {"  a", 11},
          {"    b", 6},
          {"      2", 5},
          {"    3", 2},
        });
  }

  /*
   *  [a       ]   [a        ]
   *   [1]           [1]
   *       [2]           [c ]
   *        [b]          [b ]  <- b should steal 2 but not 1 from a
   *        [c]          [2]
   */
  @Test
  void testDectivationBeforeEnd_DontStealChildIdsOfUnrelatedActivations() throws Exception {
    Map<String, SpanData> spans =
        assertCallTree(
            new String[] {"      c c ", "      b b ", "a   a a aa", " 1 1 2 2  "},
            new Object[][] {
              {"a", 5},
              {"  b", 2},
              {"    c", 2},
            },
            new Object[][] {
              {"a", 9},
              {"  1", 2},
              {"  c", 3, Arrays.asList("b")},
              {"    2", 2},
            });
    assertThat(spans.get("a").getLinks())
        .hasSize(1)
        .anySatisfy(link -> assertThat(link.getAttributes()).containsEntry(LINK_IS_CHILD, true));
    assertThat(spans.get("c").getLinks())
        .hasSize(1)
        .anySatisfy(link -> assertThat(link.getAttributes()).containsEntry(LINK_IS_CHILD, true));
  }

  /*
   *  [a         ]   [a         ]
   *   [1]            [1]
   *       [2  ]           [c  ]  <- this is an open issue: c should start when 2 starts but starts with 3 starts
   *        [3]           [2  ]
   *         [c ]          [3]
   */
  @Test
  void testDectivationBeforeEnd_DontStealChildIdsOfUnrelatedActivations_Nested() throws Exception {
    Map<String, SpanData> spans =
        assertCallTree(
            new String[] {"       c  c ", "       b  b ", "a   a  a  aa", " 1 1 23 32  "},
            new Object[][] {
              {"a", 5},
              {"  b", 2},
              {"    c", 2},
            },
            new Object[][] {
              {"a", 11},
              {"  1", 2},
              {"  c", 4, Arrays.asList("b")},
              {"    2", 4},
              {"      3", 2},
            });
    assertThat(spans.get("a").getLinks())
        .hasSize(1)
        .anySatisfy(link -> assertThat(link.getAttributes()).containsEntry(LINK_IS_CHILD, true));
    assertThat(spans.get("c").getLinks())
        .hasSize(1)
        .anySatisfy(link -> assertThat(link.getAttributes()).containsEntry(LINK_IS_CHILD, true));
  }

  /*
   * [a ]      [a  ]
   * [b[1] - > [b[1]
   */
  @Test
  void testActivationAfterMethodEnds() throws Exception {
    assertCallTree(
        new String[] {"bb   ", "aa a ", "  1 1"},
        new Object[][] {
          {"a", 3},
          {"  b", 2},
        },
        new Object[][] {
          {"a", 3},
          {"  b", 1},
          {"  1", 2}
        });
  }

  /*
   * [a   ]
   * [b[1]
   */
  @Test
  void testActivationBetweenMethods() throws Exception {
    assertCallTree(
        new String[] {"bb   ", "aa  a", "  11 "},
        new Object[][] {
          {"a", 3},
          {"  b", 2},
        },
        new Object[][] {
          {"a", 4},
          {"  b", 1},
          {"  1", 1},
        });
  }

  /*
   * [a   ]
   * [b[1]
   *  c
   */
  @Test
  void testActivationBetweenMethods_AfterFastMethod() throws Exception {
    assertCallTree(
        new String[] {" c   ", "bb   ", "aa  a", "  11 "},
        new Object[][] {
          {"a", 3},
          {"  b", 2},
        },
        new Object[][] {
          {"a", 4},
          {"  b", 1},
          {"  1", 1},
        });
  }

  /*
   * [a ]
   * [b]
   *  1
   */
  @Test
  void testActivationBetweenFastMethods() throws Exception {
    assertCallTree(
        new String[] {"c  d   ", "b  b   ", "a  a  a", " 11 22 "},
        new Object[][] {
          {"a", 3},
          {"  b", 2},
        },
        new Object[][] {
          {"a", 6},
          {"  b", 3},
          {"    1", 1},
          {"  2", 1},
        });
  }

  /*    */
  /*
   * [a       ]
   * [b] [1 [c]
   */
  /*
  @Test
  void testActivationBetweenMethods_WithCommonAncestor() throws Exception {
      assertCallTree(new String[]{
          "  c     f  g ",
          "bbb  e  d  dd",
          "aaa  a  a  aa",
          "   11 22 33  "
      }, new Object[][] {
          {"a",   7},
          {"  b", 3},
          {"  d", 3},
      }, new Object[][] {
          {"a",     12},
          {"  b",   2},
          {"  1",   1},
          {"  2",   1},
          {"  d",   4},
          {"    3", 1},
      });
  }*/

  /*
   * [a    ]
   *  [1  ]
   *   [2]
   */
  @Test
  void testNestedActivation() throws Exception {
    assertCallTree(
        new String[] {"a  a  a", " 12 21 "},
        new Object[][] {
          {"a", 3},
        },
        new Object[][] {
          {"a", 6},
          {"  1", 4},
          {"    2", 2},
        });
  }

  /*
   * [1         ]
   *  [a][2    ]
   *  [b] [3  ]
   *       [c]
   */
  @Test
  void testNestedActivationAfterMethodEnds_RootChangesToC() throws Exception {
    Map<String, SpanData> spans =
        assertCallTree(
            new String[] {" bbb        ", " aaa  ccc   ", "1   23   321"},
            new Object[][] {
              {"a", 3},
              {"  b", 3},
              {"c", 3},
            },
            new Object[][] {
              {"1", 11},
              {"  b", 2, Arrays.asList("a")},
              {"  2", 6},
              {"    3", 4},
              {"      c", 2}
            });

    assertThat(spans.get("b").getLinks()).isEmpty();
  }

  /*
   * [1           ]
   *  [a  ][3    ]
   *  [b  ] [4  ]
   *   [2]   [c]
   */
  @Test
  void testRegularActivationFollowedByNestedActivationAfterMethodEnds() throws Exception {
    assertCallTree(
        new String[] {"   d          ", " b b b        ", " a a a  ccc   ", "1 2 2 34   431"},
        new Object[][] {
          {"a", 3},
          {"  b", 3},
          {"c", 3},
        },
        new Object[][] {
          {"1", 13},
          {"  b", 4, Arrays.asList("a")},
          {"    2", 2},
          {"  3", 6},
          {"    4", 4},
          {"      c", 2}
        });
  }

  /*
   * [1             ]
   *  [a           ]
   *   [b  ][3    ]
   *    [2]  [4  ]
   *          [c]
   */
  @Test
  void testNestedActivationAfterMethodEnds_CommonAncestorA() throws Exception {
    Map<String, SpanData> spans =
        assertCallTree(
            new String[] {"  b b b  ccc    ", " aa a a  aaa  a ", "1  2 2 34   43 1"},
            new Object[][] {
              {"a", 8},
              {"  b", 3},
              {"  c", 3},
            },
            new Object[][] {
              {"1", 15},
              {"  a", 13},
              {"    b", 4},
              {"      2", 2},
              {"    3", 6},
              {"      4", 4},
              {"        c", 2}
            });

    assertThat(spans.get("b").getLinks())
        .hasSize(1)
        .anySatisfy(
            link -> {
              assertThat(link.getAttributes()).containsEntry(LINK_IS_CHILD, true);
              SpanData expectedSpan = spans.get("2");
              assertThat(link.getSpanContext().getTraceId()).isEqualTo(expectedSpan.getTraceId());
              assertThat(link.getSpanContext().getSpanId()).isEqualTo(expectedSpan.getSpanId());
            });

    assertThat(spans.get("c").getLinks()).isEmpty();

    assertThat(spans.get("a").getLinks())
        .hasSize(1)
        .anySatisfy(
            link -> {
              assertThat(link.getAttributes()).containsEntry(LINK_IS_CHILD, true);
              SpanData expectedSpan = spans.get("3");
              assertThat(link.getSpanContext().getTraceId()).isEqualTo(expectedSpan.getTraceId());
              assertThat(link.getSpanContext().getSpanId()).isEqualTo(expectedSpan.getSpanId());
            });
  }

  /*
   * [1       ]
   *  [a]
   *     [2  ]
   *      [b]
   *      [c]
   */
  @Test
  void testActivationAfterMethodEnds_RootChangesToB() throws Exception {
    assertCallTree(
        new String[] {"     ccc  ", " aaa bbb  ", "1   2   21"},
        new Object[][] {
          {"a", 3},
          {"b", 3},
          {"  c", 3},
        },
        new Object[][] {
          {"1", 9},
          {"  a", 2},
          {"  2", 4},
          {"    c", 2, Arrays.asList("b")}
        });
  }

  /*
   * [1       ]
   *  [a]
   *     [2  ]
   *      [b]
   */
  @Test
  void testActivationAfterMethodEnds_RootChangesToB2() throws Exception {
    assertCallTree(
        new String[] {" aaa bbb  ", "1   2   21"},
        new Object[][] {
          {"a", 3},
          {"b", 3},
        },
        new Object[][] {
          {"1", 9},
          {"  a", 2},
          {"  2", 4},
          {"    b", 2}
        });
  }

  /*
   * [1]
   *  [a]
  @Test
  void testActivationBeforeCallTree() throws Exception {
      assertCallTree(new String[]{
          " aaa",
          "1 1 "
      }, new Object[][] {
          {"a",   3},
      }, new Object[][] {
          {"a",   3},
          {"  1", 2},
      });
  }     */

  /*
   * [1       ]
   *  [a     ]
   *     [2  ]
   *      [b]
   *      [c]
   */
  @Test
  void testActivationAfterMethodEnds_SameRootDeeperStack() throws Exception {
    assertCallTree(
        new String[] {"     ccc  ", " aaa aaa  ", "1   2   21"},
        new Object[][] {
          {"a", 6},
          {"  c", 3},
        },
        new Object[][] {
          {"1", 9},
          {"  a", 6},
          {"    2", 4},
          {"      c", 2}
        });
  }

  /*
   * [1     ]
   *  [a   ]
   *   [2 ]
   *    [b]
   */
  @Test
  void testActivationBeforeMethodStarts() throws Exception {
    assertCallTree(
        new String[] {"   bbb   ", " a aaa a ", "1 2   2 1"},
        new Object[][] {
          {"a", 5},
          {"  b", 3},
        },
        new Object[][] {
          {"1", 8},
          {"  a", 6},
          {"    2", 4},
          {"      b", 2}
        });
  }

  /*
   * [1        ]    [1        ]
   *  [a      ]      [a      ]
   *   [b   ]    ->   [b    ]
   *    [c  ]    ->    [c   ]
   *     [2  ]          [2  ]
   *      []             []
   */
  @Test
  void testDectivationAfterEnd() throws Exception {
    assertCallTree(
        new String[] {
          "     dd     ",
          "   c ccc    ",
          "  bb bbb    ", // <- deactivation for span 2 happens after b ends
          " aaa aaa aa ", //    that means b must have ended after 2 has been deactivated
          "1   2   2  1" //    but we saw the last stack trace of b before the deactivation of 2
        },
        new Object[][] {
          {"a", 8},
          {"  b", 5},
          {"    c", 4},
          {"      d", 2},
        },
        new Object[][] {
          {"1", 11},
          {"  a", 9},
          {"    b", 6},
          {"      c", 5},
          {"        2", 4},
          {"          d", 1},
        });
  }

  @Test
  void testCallTreeActivationAsParentOfFastSpan() throws Exception {
    assertCallTree(
        new String[] {"    b    ", " aa a aa ", "1  2 2  1"},
        new Object[][] {{"a", 5}},
        new Object[][] {
          {"1", 8},
          {"  a", 6},
          {"    2", 2},
        });
  }

  @Test
  void testCallTreeActivationAsChildOfFastSpan() throws Exception {
    profilerSetup.close();
    profilerSetup =
        ProfilerTestSetup.create(
            config ->
                config
                    .inferredSpansMinDuration(Duration.ofMillis(50))
                    .clock(nanoClock)
                    .startScheduledProfiling(false));
    profilerSetup.profiler.setProfilingSessionOngoing(true);
    assertCallTree(
        new String[] {"   c  c   ", "   b  b   ", " aaa  aaa ", "1   22   1"},
        new Object[][] {{"a", 6}},
        new Object[][] {
          {"1", 9},
          {"  a", 7},
          {"    2", 1},
        });
  }

  @Test
  void testCallTreeActivationAsLeaf() throws Exception {
    assertCallTree(
        new String[] {" aa  aa ", "1  22  1"},
        new Object[][] {{"a", 4}},
        new Object[][] {
          {"1", 7},
          {"  a", 5},
          {"    2", 1},
        });
  }

  @Test
  void testCallTreeMultipleActivationsAsLeaf() throws Exception {
    assertCallTree(
        new String[] {" aa  aaa  aa ", "1  22   33  1"},
        new Object[][] {{"a", 7}},
        new Object[][] {
          {"1", 12},
          {"  a", 10},
          {"    2", 1},
          {"    3", 1},
        });
  }

  @Test
  void testCallTreeMultipleActivationsAsLeafWithExcludedParent() throws Exception {
    profilerSetup.close();
    profilerSetup =
        ProfilerTestSetup.create(
            config ->
                config
                    .clock(nanoClock)
                    .startScheduledProfiling(false)
                    .inferredSpansMinDuration(Duration.ofMillis(50)));
    profilerSetup.profiler.setProfilingSessionOngoing(true);
    // min duration 4
    assertCallTree(
        new String[] {"  b  b c  c  ", " aa  aaa  aa ", "1  22   33  1"},
        new Object[][] {{"a", 7}},
        new Object[][] {
          {"1", 12},
          {"  a", 10},
          {"    2", 1},
          {"    3", 1},
        });
  }

  @Test
  void testCallTreeMultipleActivationsWithOneChild() throws Exception {
    assertCallTree(
        new String[] {"         bb    ", " aa  aaa aa aa ", "1  22   3  3  1"},
        new Object[][] {
          {"a", 9},
          {"  b", 2}
        },
        new Object[][] {
          {"1", 14},
          {"  a", 12},
          {"    2", 1},
          {"    3", 3},
          {"      b", 1},
        });
  }

  /*
   * [1   ]     [1   ]
   *  [2]   ->   [a ]
   *   [a]       [2]
   *
   * Note: this test is currently failing
   */
  @Test
  @Disabled("fix me")
  void testNestedActivationBeforeCallTree() throws Exception {
    assertCallTree(
        new String[] {"  aaa ", "12 2 1"},
        new Object[][] {
          {"a", 3},
        },
        new Object[][] {
          {"1", 5},
          {"  a", 3}, // a is actually a child of the transaction
          {"    2", 2}, // 2 is not within the child_ids of a
        });
  }

  private void assertCallTree(String[] stackTraces, Object[][] expectedTree) throws Exception {
    assertCallTree(stackTraces, expectedTree, null);
  }

  @SuppressWarnings({"unchecked", "ReturnsNullCollection"})
  private Map<String, SpanData> assertCallTree(
      String[] stackTraces, Object[][] expectedTree, @Nullable Object[][] expectedSpans)
      throws Exception {
    CallTree.Root root = getCallTree(profilerSetup, stackTraces);
    StringBuilder expectedResult = new StringBuilder();
    for (int i = 0; i < expectedTree.length; i++) {
      Object[] objects = expectedTree[i];
      expectedResult.append(objects[0]).append(" ").append(objects[1]);
      if (i != expectedTree.length - 1) {
        expectedResult.append("\n");
      }
    }

    String actualResult = root.toString().replace(CallTreeTest.class.getName() + ".", "");
    actualResult =
        Arrays.stream(actualResult.split("\n"))
            // skip root node
            .skip(1)
            // trim first two spaces
            .map(s -> s.substring(2))
            .collect(Collectors.joining("\n"));

    assertThat(actualResult).isEqualTo(expectedResult.toString());

    if (expectedSpans != null) {
      root.spanify(
          nanoClock,
          profilerSetup.sdk.getTracer("dummy-inferred-spans-tracer"),
          CallTree.DEFAULT_PARENT_OVERRIDE);
      Map<String, SpanData> spans =
          profilerSetup.getSpans().stream()
              .collect(toMap(s -> s.getName().replaceAll(".*#", ""), Function.identity()));
      assertThat(profilerSetup.getSpans()).hasSize(expectedSpans.length + 1);

      for (int i = 0; i < expectedSpans.length; i++) {
        Object[] expectedSpan = expectedSpans[i];
        String spanName = ((String) expectedSpan[0]).trim();
        int durationMs = (int) expectedSpan[1] * 10;
        List<String> stackTrace =
            expectedSpan.length == 3 ? (List<String>) expectedSpan[2] : Arrays.asList();
        int nestingLevel = getNestingLevel((String) expectedSpan[0]);
        String parentName = getParentName(expectedSpans, i, nestingLevel);
        if (parentName == null) {
          parentName = "Call Tree Root";
        }
        assertThat(spans).containsKey(spanName);
        assertThat(spans).containsKey(parentName);
        SpanData span = spans.get(spanName);
        assertThat(isChild(spans.get(parentName), span))
            .withFailMessage(
                "Expected %s (%s) to be a child of %s (%s) but was %s (%s)",
                spanName,
                span.getSpanContext().getSpanId(),
                parentName,
                spans.get(parentName).getSpanId(),
                profilerSetup.getSpans().stream()
                    .filter(s -> s.getSpanId().equals(span.getParentSpanId()))
                    .findAny()
                    .map(SpanData::getName)
                    .orElse(null),
                span.getParentSpanId())
            .isTrue();
        assertThat(isChild(span, spans.get(parentName)))
            .withFailMessage(
                "Expected %s (%s) to not be a child of %s (%s) but was %s (%s)",
                parentName,
                spans.get(parentName).getSpanId(),
                spanName,
                span.getSpanId(),
                profilerSetup.getSpans().stream()
                    .filter(s -> s.getSpanId().equals(span.getParentSpanId()))
                    .findAny()
                    .map(SpanData::getName)
                    .orElse(null),
                span.getParentSpanId())
            .isFalse();
        assertThat(span.getEndEpochNanos() - span.getStartEpochNanos())
            .describedAs("Unexpected duration for span %s", span)
            .isEqualTo(durationMs * 1_000_000L);

        String actualStacktrace = span.getAttributes().get(CodeAttributes.CODE_STACKTRACE);
        if (stackTrace == null || stackTrace.isEmpty()) {
          assertThat(actualStacktrace).isBlank();
        } else {
          String expected =
              stackTrace.stream()
                  .map(
                      funcName ->
                          "at "
                              + CallTreeTest.class.getName()
                              + "."
                              + funcName
                              + "(CallTreeTest.java)")
                  .collect(Collectors.joining("\n"));
          assertThat(actualStacktrace).isEqualTo(expected);
        }
      }
      return spans;
    }
    return null;
  }

  public boolean isChild(SpanData parent, SpanData expectedChild) {
    if (!parent.getTraceId().equals(expectedChild.getTraceId())) {
      return false;
    }
    if (parent.getSpanId().equals(expectedChild.getParentSpanId())) {
      return true;
    }
    for (LinkData link : parent.getLinks()) {
      Boolean isChild = link.getAttributes().get(LINK_IS_CHILD);
      if (isChild != null && isChild) {
        SpanContext linkSpanCtx = link.getSpanContext();
        if (linkSpanCtx.getTraceId().equals(expectedChild.getTraceId())
            && linkSpanCtx.getSpanId().equals(expectedChild.getSpanId())) {
          return true;
        }
      }
    }

    return false;
  }

  @Nullable
  private static String getParentName(@Nonnull Object[][] expectedSpans, int i, int nestingLevel) {
    if (nestingLevel > 0) {
      for (int j = i - 1; j >= 0; j--) {
        String name = (String) expectedSpans[j][0];
        boolean isParent = getNestingLevel(name) == nestingLevel - 1;
        if (isParent) {
          return name.trim();
        }
      }
    }
    return null;
  }

  private static int getNestingLevel(String spanName) {
    // nesting is denoted by two spaces
    return (spanName.length() - 1) / 2;
  }

  public static CallTree.Root getCallTree(ProfilerTestSetup profilerSetup, String[] stackTraces)
      throws Exception {
    SamplingProfiler profiler = profilerSetup.profiler;
    FixedClock nanoClock = (FixedClock) profilerSetup.profiler.getClock();
    nanoClock.setNanoTime(1);
    profiler.setProfilingSessionOngoing(true);

    CallTree.Root root = null;
    ObjectPool<CallTree> callTreePool = ObjectPool.createRecyclable(2, CallTree::new);
    Map<String, Span> spanMap = new HashMap<>();
    Map<String, Scope> spanScopeMap = new HashMap<>();

    Tracer tracer = profilerSetup.sdk.getTracer("testing-tracer");

    Span transaction =
        tracer.spanBuilder("Call Tree Root").setStartTimestamp(1, TimeUnit.NANOSECONDS).startSpan();
    try (Scope scope = transaction.makeCurrent()) {
      List<StackTraceEvent> stackTraceEvents = new ArrayList<>();
      for (int i = 0; i < stackTraces[0].length(); i++) {
        nanoClock.setNanoTime(1 + i * TimeUnit.MILLISECONDS.toNanos(10));
        List<StackFrame> trace = new ArrayList<>();
        for (String stackTrace : stackTraces) {
          char c = stackTrace.charAt(i);
          if (Character.isDigit(c)) {
            handleSpanEvent(
                tracer, spanMap, spanScopeMap, Character.toString(c), nanoClock.nanoTime());
            break;
          } else if (!Character.isSpaceChar(c)) {
            trace.add(StackFrame.of(CallTreeTest.class.getName(), Character.toString(c)));
          }
        }
        if (!trace.isEmpty()) {
          stackTraceEvents.add(new StackTraceEvent(trace, nanoClock.nanoTime()));
        }
      }

      profiler.consumeActivationEventsFromRingBufferAndWriteToFile();
      long eof = profiler.startProcessingActivationEventsFile();
      for (StackTraceEvent stackTraceEvent : stackTraceEvents) {
        profiler.processActivationEventsUpTo(stackTraceEvent.nanoTime, eof);
        if (root == null) {
          root = profiler.getRoot();
          assertThat(root).isNotNull();
        }
        long millis = profilerSetup.profiler.getConfig().getInferredSpansMinDuration().toMillis();
        root.addStackTrace(
            stackTraceEvent.trace,
            stackTraceEvent.nanoTime,
            callTreePool,
            TimeUnit.MILLISECONDS.toNanos(millis));
      }

    } finally {
      transaction.end();
    }

    assertThat(root).isNotNull();
    root.end(callTreePool, 0);
    return root;
  }

  private static class StackTraceEvent {

    private final List<StackFrame> trace;
    private final long nanoTime;

    public StackTraceEvent(List<StackFrame> trace, long nanoTime) {

      this.trace = trace;
      this.nanoTime = nanoTime;
    }
  }

  @SuppressWarnings("MustBeClosedChecker")
  private static void handleSpanEvent(
      Tracer tracer,
      Map<String, Span> spanMap,
      Map<String, Scope> spanScopeMap,
      String name,
      long nanoTime) {
    if (!spanMap.containsKey(name)) {
      Span span =
          tracer
              .spanBuilder(name)
              .setParent(Context.current())
              .setStartTimestamp(nanoTime, TimeUnit.NANOSECONDS)
              .startSpan();
      spanMap.put(name, span);
      spanScopeMap.put(name, span.makeCurrent());
    } else {
      spanScopeMap.remove(name).close();
      spanMap.get(name).end(nanoTime, TimeUnit.NANOSECONDS);
    }
  }
}
