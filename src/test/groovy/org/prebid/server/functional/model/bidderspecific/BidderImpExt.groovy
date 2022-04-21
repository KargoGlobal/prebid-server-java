package org.prebid.server.functional.model.bidderspecific

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import groovy.transform.ToString
import org.prebid.server.functional.model.bidder.Generic
import org.prebid.server.functional.model.request.auction.ImpExt

@ToString(includeNames = true, ignoreNulls = true)
@JsonNaming(PropertyNamingStrategies.LowerCaseStrategy)
class BidderImpExt extends ImpExt {

    Generic bidder
    Rp rp
    String maxBids
}
