package com.fisiodesk.smartquery.analysis;

/**
 * Coarse anatomical areas we tag notes with. Enough granularity for the
 * queries the product needs today; extending the taxonomy only means adding
 * terms to the lexicon (or letting the LLM map to one of these values).
 */
public enum BodyArea {
    LUMBAR,
    CERVICAL,
    SHOULDER,
    KNEE,
    HIP,
    OTHER
}
