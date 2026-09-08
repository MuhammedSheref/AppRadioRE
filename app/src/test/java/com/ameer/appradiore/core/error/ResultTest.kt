package com.ameer.appradiore.core.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultTest {

    @Test
    fun testSuccessChaining() {
        var sideEffectRun = false
        val result: Result<Int, DataError.Usb> = Result.Success(42)

        val mapped = result
            .onSuccess {
                sideEffectRun = true
                assertEquals(42, it)
            }
            .onFailure {
                throw AssertionError("Should not be called")
            }
            .map { it * 2 }

        assertTrue(sideEffectRun)
        assertTrue(mapped is Result.Success)
        assertEquals(84, (mapped as Result.Success).data)
    }

    @Test
    fun testErrorChaining() {
        var failureRun = false
        val result: Result<Int, DataError.Usb> = Result.Error(DataError.Usb.PERMISSION_DENIED)

        val mapped = result
            .onSuccess {
                throw AssertionError("Should not be called")
            }
            .onFailure {
                failureRun = true
                assertEquals(DataError.Usb.PERMISSION_DENIED, it)
            }
            .map { it * 2 }

        assertTrue(failureRun)
        assertTrue(mapped is Result.Error)
        assertEquals(DataError.Usb.PERMISSION_DENIED, (mapped as Result.Error).error)
    }

    @Test
    fun testAsEmptyResult() {
        val successResult: Result<String, DataError.Protocol> = Result.Success("Connected")
        val empty = successResult.asEmptyResult()
        assertTrue(empty is Result.Success)
        assertEquals(Unit, (empty as Result.Success).data)
    }

    @Test
    fun testErrorToUiText() {
        val uiText = DataError.Usb.PERMISSION_DENIED.toUiText()
        assertTrue(uiText is com.ameer.appradiore.core.presentation.UiText.DynamicString)
        assertFalse((uiText as com.ameer.appradiore.core.presentation.UiText.DynamicString).value.isBlank())
    }
}
