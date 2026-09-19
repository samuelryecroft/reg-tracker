/**
 * Vocabulary shared by every feature package and depending on none of them.
 *
 * <p>T378 seeds it with the three exceptions the web layer maps to a client-side status;
 * CODE-REVIEW-2026-09-18 §5 proposes moving the identity and tenancy types here too, so that
 * {@code config} and {@code audit} can stop importing from the packages they serve. Nothing in
 * this package may import from a feature package - that is the whole of its contract.
 */
package ninja.samryecroft.returnhome.tracker.core;
