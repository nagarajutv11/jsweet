/* 
 * JSweet - http://www.jsweet.org
 * Copyright (C) 2015 CINCHEO SAS <renaud.pawlak@cincheo.fr>
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jsweet.transpiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;

import org.jsweet.JSweetConfig;
import org.jsweet.transpiler.util.AbstractTreeScanner;
import org.jsweet.transpiler.util.Util;

import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.TypeSymbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCExpressionStatement;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCLiteral;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCReturn;
import com.sun.tools.javac.tree.JCTree.JCStatement;

/**
 * This AST scanner detects method overloads and gather them into
 * {@link Overload} objects.
 * 
 * @author Renaud Pawlak
 */
public class OverloadScanner extends AbstractTreeScanner {

	Types types;
	int pass = 1;

	/**
	 * Gathers methods overloading each other.
	 * 
	 * @author Renaud Pawlak
	 */
	public class Overload {
		/**
		 * The method name.
		 */
		public String methodName;
		/**
		 * The methods carrying the same name.
		 */
		public List<JCMethodDecl> methods = new ArrayList<>();
		/**
		 * Tells if this overload is valid wrt to JSweet conventions.
		 */
		public boolean isValid = true;
		/**
		 * The core method of the overload, that is to say the one holding the
		 * implementation.
		 */
		public JCMethodDecl coreMethod;
		/**
		 * The default values for the parameters of the core method.
		 */
		public Map<Integer, JCTree> defaultValues;

		/**
		 * Checks the validity of the overload and calculates the default
		 * values.
		 */
		public void calculate() {
			if (methods.size() < 2) {
				return;
			}
			methods.sort((m1, m2) -> {
				int i = m2.getParameters().size() - m1.getParameters().size();
				if (i == 0) {
					isValid = false;
				}
				return i;
			});
			coreMethod = methods.get(0);
			if (isValid) {
				defaultValues = new HashMap<>();
			}
		}
	}

	/**
	 * Creates a new overload scanner.
	 */
	public OverloadScanner(TranspilationHandler logHandler, JSweetContext context) {
		super(logHandler, context, null);
		this.types = Types.instance(context);
	}

	/**
	 * Gets an overload instance for the given class and method.
	 */
	public Overload getOverload(ClassSymbol clazz, JCMethodDecl method) {
		Map<String, Overload> m = context.overloads.get(clazz);
		if (m == null) {
			m = new HashMap<>();
			context.overloads.put(clazz, m);
		}
		String name = method.name.toString();
		Overload overload = m.get(name);
		if (overload == null) {
			overload = new Overload();
			overload.methodName = name;
			m.put(name, overload);
		}
		return overload;
	}

	@Override
	public void visitClassDef(JCClassDecl classdecl) {
		if (classdecl.sym.isInterface() || Util.hasAnnotationType(classdecl.sym, JSweetConfig.ANNOTATION_INTERFACE)) {
			return;
		}
		if (pass == 1) {
			ClassSymbol clazz = classdecl.sym;
			for (JCTree member : classdecl.defs) {
				if (member instanceof JCMethodDecl) {
					JCMethodDecl method = (JCMethodDecl) member;
					Overload overload = getOverload(clazz, method);
					overload.methods.add(method);
				}
				// scan inner classes
				if (member instanceof JCClassDecl) {
					scan(member);
				}
			}
		} else {
			for (JCTree member : classdecl.defs) {
				if (member instanceof JCMethodDecl || member instanceof JCClassDecl) {
					this.scan(member);
				}
			}
		}
	}

	@Override
	public void visitMethodDef(JCMethodDecl methodDecl) {
		Element e = methodDecl.sym.getEnclosingElement();
		if (!(e instanceof ClassSymbol)) {
			return;
		}
		Overload overload = context.getOverload(((ClassSymbol) e), methodDecl.name.toString());
		if (overload != null && overload.methods.size() > 1 && overload.isValid) {
			if (!methodDecl.equals(overload.coreMethod)) {
				if (methodDecl.body != null && methodDecl.body.stats.size() == 1) {
					JCMethodInvocation invocation = null;
					JCStatement stat = methodDecl.body.stats.get(0);
					if (stat instanceof JCReturn) {
						if (((JCReturn) stat).expr instanceof JCMethodInvocation) {
							invocation = (JCMethodInvocation) ((JCReturn) stat).expr;
						}
					} else if (stat instanceof JCExpressionStatement) {
						if (((JCExpressionStatement) stat).expr instanceof JCMethodInvocation) {
							invocation = (JCMethodInvocation) ((JCExpressionStatement) stat).expr;
						}
					}
					if (invocation == null) {
						overload.isValid = false;
					} else {
						MethodSymbol method = Util.findMethodDeclarationInType(types, (TypeSymbol) e, invocation);
						if (method != null && method.getSimpleName().toString().equals(overload.methodName)) {
							if (invocation.getArguments() != null) {
								for (int i = 0; i < invocation.getArguments().size(); i++) {
									JCExpression expr = invocation.getArguments().get(i);
									boolean constant = false;
									if (expr instanceof JCLiteral) {
										constant = true;
									} else if (expr instanceof JCFieldAccess) {
										if (((JCFieldAccess) expr).sym.isStatic() && ((JCFieldAccess) expr).sym.getModifiers().contains(Modifier.FINAL)) {
											constant = true;
										}
									} else if (expr instanceof JCIdent) {
										if (((JCIdent) expr).sym.isStatic() && ((JCIdent) expr).sym.getModifiers().contains(Modifier.FINAL)) {
											constant = true;
										}
									}
									if (constant) {
										overload.defaultValues.put(i, expr);
									} else {
										if (!(expr instanceof JCIdent
												&& methodDecl.params.stream().map(p -> p.name).anyMatch(p -> p.equals(((JCIdent) expr).name)))) {
											overload.isValid = false;
										}
									}
								}
							}
						} else {
							overload.isValid = false;
						}
					}
				} else {
					overload.isValid = false;
				}
			}
		}
	}

	@Override
	public void visitTopLevel(JCCompilationUnit cu) {
		setCompilationUnit(cu);
		super.visitTopLevel(cu);
		setCompilationUnit(null);
	}

	/**
	 * Processes all the overload of a given compilation unit.
	 */
	public void process(JCCompilationUnit cu) {
		scan(cu);
		for (Map<String, Overload> overloads : context.overloads.values()) {
			for (Overload overload : overloads.values()) {
				overload.calculate();
			}
		}
		pass++;
		scan(cu);
	}

}
