// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.java.codeInsight.completion

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.InjectedLanguagePlaces
import com.intellij.psi.LanguageInjector
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.CompletionAutoPopupTestCase
import groovy.transform.CompileStatic
import org.jetbrains.annotations.NotNull

/**
 * For tests checking platform behavior not related to Java language (but they may still use Java for code samples)
 */
@CompileStatic
class GeneralAutoPopupTest extends CompletionAutoPopupTestCase {
  void "test no autopopup in the middle of word when the only variant is already in the editor"() {
    myFixture.configureByText 'a.java', 'class Foo { private boolean ignoredProperty; public boolean isIgnoredP<caret>operty() {}}'
    type 'r'
    assert !lookup
  }

  void "test no lookup after typing a letter and then quickly overtyping a quote"() {
    myFixture.configureByText 'a.html', '<a href="<caret>">'
    myFixture.type('a')
    type '"'
    assert !lookup
  }

  void "test no lookup after typing and quickly moving caret to another place"() {
    myFixture.configureByText 'a.java', 'class Foo { <caret> }'
    edt {
      myFixture.type('F')
      myFixture.editor.caretModel.moveToOffset(myFixture.caretOffset + 1)
    }

    myTester.joinAutopopup()
    myTester.joinCompletion()

    assert !lookup
  }

  void "test injectors are not run in EDT"() {
    boolean injectorCalled = false
    LanguageInjector injector = new LanguageInjector() {
      @Override
      void getLanguagesToInject(@NotNull PsiLanguageInjectionHost host, @NotNull InjectedLanguagePlaces injectionPlacesRegistrar) {
        injectorCalled = true
        assert !ApplicationManager.application.dispatchThread
      }
    }
    ExtensionTestUtil.maskExtensions(LanguageInjector.EXTENSION_POINT_NAME, [injector] as List<LanguageInjector>, myFixture.testRootDisposable)

    myFixture.configureByText 'a.java', 'class Foo { String s = <caret>; }'
    assert !injectorCalled
    type '"'

    injectorCalled = false
    type 'abc'

    assert injectorCalled
    assert !lookup
  }

  void "test don't close lookup when starting a new line after dot"() {
    myFixture.configureByText 'a.java', 'class Foo {{ "abc"<caret> }}'
    type '.'
    assert lookup
    edt {
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_START_NEW_LINE)
      assert lookup
      assert lookup.lookupStart == myFixture.editor.caretModel.offset
      assert myFixture.editor.document.text.contains('\n')
    }
  }

  void "test close lookup when starting a new line after having typed an identifier manually"() {
    myFixture.configureByText 'a.java', 'class Foo { int a, ab; { new Foo()<caret> }}'
    type '.a'
    assert lookup
    edt {
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_START_NEW_LINE)
      assert !lookup
      assert myFixture.editor.document.text.contains('\n')
    }
  }
}
