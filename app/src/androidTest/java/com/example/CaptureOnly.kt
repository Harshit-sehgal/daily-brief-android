package com.example

/**
 * Marks instrumentation that publishes rather than verifies.
 *
 * Gradle-launched connected runs exclude it with `notAnnotation`, so the capture harness never
 * joins the gate, seeds rows into a device other tests are using, or changes saved settings
 * underneath them. `scripts/capture-preview.sh` drives the runner directly and so still runs it.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class CaptureOnly
