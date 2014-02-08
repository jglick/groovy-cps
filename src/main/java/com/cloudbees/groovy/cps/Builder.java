package com.cloudbees.groovy.cps;

import org.codehaus.groovy.runtime.ScriptBytecodeAdapter;
import org.codehaus.groovy.runtime.callsite.CallSite;
import org.codehaus.groovy.runtime.callsite.CallSiteArray;

import java.util.ArrayList;
import java.util.List;

import static java.util.Arrays.*;
import static java.util.Collections.*;

/**
 * @author Kohsuke Kawaguchi
 */
public class Builder {
    private static final Expression NULL = new Constant(null);

    public Expression null_() {
        return NULL;
    }

    public Expression constant(Object o) {
        return new Constant(o);
    }

    public Expression sequence(Expression... bodies) {
        if (bodies.length==0)   return NULL;

        Expression e = bodies[0];
        for (int i=1; i<bodies.length; i++)
            e = sequence(e,bodies[i]);
        return e;
    }

    public Expression sequence(final Expression exp1, final Expression exp2) {
        return new Expression() {
            public Next eval(final Env e, final Continuation k) {
                return new Next(exp1,e,new Continuation() {
                    public Next receive(Object __) {
                        return new Next(exp2,e,k);
                    }
                });
            }
        };
    }

//    public Expression compareLessThan(final Expression lhs, final Expression rhs) {
//
//    }

    public Expression getLocalVariable(final String name) {
        return new Expression() {
            public Next eval(Env e, Continuation k) {
                return k.receive(e.getLocalVariable(name));
            }
        };
    }

    public Expression setLocalVariable(final String name, final Expression rhs) {
        return new Expression() {
            public Next eval(final Env e, final Continuation k) {
                return rhs.eval(e,new Continuation() {
                    public Next receive(Object o) {
                        e.setLocalVariable(name, o);
                        return k.receive(o);
                    }
                });
            }
        };
    }

    /**
     * Assignment operator to a local variable, such as "x += 3"
     */
    public Expression localVariableAssignOp(String name, String operator, Expression rhs) {
        return setLocalVariable(name, functionCall(getLocalVariable(name),operator,rhs));
    }

    /**
     * if (...) { ... } else { ... }
     */
    public Expression if_(final Expression cond, final Expression then, final Expression els) {
        return new Expression() {
            public Next eval(final Env e, final Continuation k) {
                return cond.eval(e,new Continuation() {
                    public Next receive(Object o) {
                        return (asBoolean(o) ? then : els).eval(e,k);
                    }
                });
            }
        };
    }

    /**
     * for (e1; e2; e3) { ... }
     */
    public Expression forLoop(final Expression e1, final Expression e2, final Expression e3, final Expression body) {
        return new Expression() {
            public Next eval(Env _e, final Continuation loopEnd) {
                final Env e = new BlockScopeEnv(_e);   // a for-loop creates a new scope for variables declared in e1,e2, & e3

                final Continuation loopHead = new Continuation() {
                    final Continuation _loopHead = this;    // because 'loopHead' cannot be referenced from within the definition

                    public Next receive(Object __) {
                        return new Next(e2,e,new Continuation() {// evaluate e2
                            public Next receive(Object v2) {
                                if (asBoolean(v2)) {
                                    // loop
                                    return new Next(body,e,new Continuation() {
                                        public Next receive(Object o) {
                                            return new Next(e3,e,_loopHead);
                                        }
                                    });
                                } else {
                                    // exit loop
                                    return loopEnd.receive(null);
                                }
                            }
                        });
                    }
                };

                return e1.eval(e,loopHead);
            }
        };
    }


    public Expression tryCatch(Expression body, CatchExpression... catches) {
        return tryCatch(body, asList(catches));
    }

    /**
     * try {
     *     ...
     * } catch (T v) {
     *     ...
     * } catch (T v) {
     *     ...
     * }
     */
    public Expression tryCatch(final Expression body, final List<CatchExpression> catches) {
        return new Expression() {
            public Next eval(final Env e, final Continuation k) {
                final TryBlockEnv f = new TryBlockEnv(e);
                for (final CatchExpression c : catches) {
                    f.addHandler(c.type, new Continuation() {
                        public Next receive(Object t) {
                            BlockScopeEnv b = new BlockScopeEnv(e);
                            b.declareVariable(c.name);
                            b.setLocalVariable(c.name, t);

                            return new Next(c.handler, b, k);
                        }
                    });
                }

                // evaluate the body with the new environment
                return new Next(body,f,k);
            }
        };
    }

    /**
     * throw exp;
     */
    public Expression throw_(final Expression exp) {
        return new Expression() {
            public Next eval(final Env e, Continuation k) {
                return new Next(exp,e,new Continuation() {
                    public Next receive(Object t) {
                        if (t==null) {
                            t = new NullPointerException();
                        }
                        // TODO: fake the stack trace information
                        // TODO: what if 't' is not Throwable?

                        Continuation v = e.getExceptionHandler(Throwable.class.cast(t).getClass());
                        return v.receive(t);
                    }
                });
            }
        };
    }


    private boolean asBoolean(Object o) {
        try {
            return (Boolean)ScriptBytecodeAdapter.asType(o,Boolean.class);
        } catch (Throwable e) {
            // TODO: exception handling
            e.printStackTrace();
            return false;
        }
    }

    public Expression staticCall(Class lhs, String name, Expression... argExps) {
        return functionCall(constant(lhs),name,argExps);
    }

    public Expression plus(Expression lhs, Expression rhs) {
        return functionCall(lhs,"plus",rhs);
    }

    public Expression lessThan(Expression lhs, Expression rhs) {
        return staticCall(ScriptBytecodeAdapter.class,"compareLessThan",lhs,rhs);
    }

    /**
     * LHS.name(...)
     */
    public Expression functionCall(final Expression lhs, final String name, Expression... argExps) {
        final CallSite callSite = fakeCallSite(name);
        final Expression args = evalArgs(argExps);

        return new Expression() {
            public Next eval(final Env e, final Continuation k) {
                return new Next(lhs,e, new Continuation() {// evaluate lhs
                    public Next receive(final Object lhs) {
                        return args.eval(e,new Continuation() {
                            public Next receive(Object _args) {
                                List args = (List) _args;

                                Object v;
                                try {
                                    v = callSite.call(lhs, args.toArray(new Object[args.size()]));
                                } catch (Throwable t) {
                                    throw new UnsupportedOperationException(t);     // TODO: exception handling
                                }

                                if (v instanceof Function) {
                                    // if this is a workflow function, it'd return a Function object instead
                                    // of actually executing the function, so execute it in the CPS
                                    return ((Function)v).invoke(e,args,k);
                                } else {
                                    // if this was a normal function, the method had just executed synchronously
                                    return k.receive(v);
                                }
                            }
                        });
                    }
                });
            }
        };
    }

//    /**
//     * name(...)
//     *
//     * TODO: is this the same as this.name(...) ? -> NO, not in closure
//     */
//    public Expression functionCall(final String name, Expression... argExps) {
//        final Expression args = evalArgs(argExps);
//        return new Expression() {
//            public Next eval(final Env e, final Continuation k) {
//                return args.eval(e,new Continuation() {
//                    public Next receive(Object args) {
//                        final Function f = e.resolveFunction(name);
//                        return f.invoke((List)args,k);
//                    }
//                });
//            }
//        };
//    }

    public Expression getProperty(Expression lhs, String property) {
        return getProperty(lhs,constant(property));
    }

    public Expression getProperty(final Expression lhs, final Expression property) {
        return new Expression() {
            public Next eval(final Env e, final Continuation k) {
                return lhs.eval(e,new Continuation() {
                    public Next receive(final Object lhs) {
                        return new Next(property,e,new Continuation() {
                            public Next receive(Object property) {
                                Object v;
                                try {
                                    // TODO: verify the behaviour of Groovy if the property expression evaluates to non-String
                                    v = ScriptBytecodeAdapter.getProperty(null/*Groovy doesn't use this parameter*/,
                                            lhs, (String) property);
                                } catch (Throwable t) {
                                    throw new UnsupportedOperationException(t);     // TODO: exception handling
                                }

                                if (v instanceof Function) {
                                    // if this is a workflow function, it'd return a Function object instead
                                    // of actually executing the function, so execute it in the CPS
                                    return ((Function)v).invoke(e, emptyList(),k);
                                } else {
                                    // if this was a normal property, we get the value as-is.
                                    return k.receive(v);
                                }
                            }
                        });
                    }
                });
            }
        };
    }

    public Expression setProperty(Expression lhs, String property, Expression rhs) {
        return setProperty(lhs, constant(property), rhs);
    }

    public Expression setProperty(final Expression lhs, final Expression property, final Expression rhs) {
        return new Expression() {
            public Next eval(final Env e, final Continuation k) {
                return lhs.eval(e,new Continuation() {
                    public Next receive(final Object lhs) {
                        return new Next(property,e,new Continuation() {
                            public Next receive(final Object property) {
                                return new Next(rhs,e,new Continuation() {
                                    public Next receive(Object rhs) {
                                        try {
                                            // TODO: verify the behaviour of Groovy if the property expression evaluates to non-String
                                            ScriptBytecodeAdapter.setProperty(rhs,
                                                    null/*Groovy doesn't use this parameter*/,
                                                    lhs, (String) property);
                                        } catch (Throwable t) {
                                            throw new UnsupportedOperationException(t);     // TODO: exception handling
                                        }
                                        return k.receive(null);
                                    }
                                });
                            }
                        });
                    }
                });
            }
        };
    }

    /**
     * Object instantiation.
     */
    public Expression new_(final Class type, Expression... argExps) {
        final CallSite callSite = fakeCallSite("<init>");
        final Expression args = evalArgs(argExps);

        return new Expression() {
            public Next eval(final Env e, final Continuation k) {
                return args.eval(e,new Continuation() {
                    public Next receive(Object _args) {
                        List args = (List) _args;

                        Object v;
                        try {
                            v = callSite.callConstructor(type, args.toArray(new Object[args.size()]));
                        } catch (Throwable t) {
                            throw new UnsupportedOperationException(t);     // TODO: exception handling
                        }

                        // constructor cannot be an asynchronous function
                        return k.receive(v);
                    }
                });
            }
        };
    }

    /**
     * Returns an expression that evaluates all the arguments and return it as a {@link List}.
     */
    private Expression evalArgs(final Expression... argExps) {
        if (argExps.length==0)  // no arguments to evaluate
            return new Constant(emptyList());

        return new Expression() {
            public Next eval(final Env e, final Continuation k) {
                final List<Object> args = new ArrayList<Object>(argExps.length); // this is where we build up actual arguments

                Next n = null;
                for (int i = argExps.length - 1; i >= 0; i--) {
                    final Next nn = n;
                    n = new Next(argExps[i], e, new Continuation() {
                        public Next receive(Object o) {
                            args.add(o);
                            if (nn != null)
                                return nn;
                            else
                                return k.receive(args);
                        }
                    });
                }
                return n;
            }
        };
    }

    /**
     * return exp;
     */
    public Expression return_(final Expression exp) {
        return new Expression() {
            public Next eval(Env e, Continuation k) {
                return new Next(exp,e, e.getReturnAddress());
            }
        };
    }

    /*TODO: specify the proper owner value (to the script that includes the call site) */
    private static CallSite fakeCallSite(String method) {
        CallSiteArray csa = new CallSiteArray(Builder.class, new String[]{method});
        return csa.array[0];
    }
}