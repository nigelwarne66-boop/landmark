package com.example.fixedassets.model;

import java.time.LocalDate;

/**
 * Session configuration loaded from CPCOYCO and GLDATES for a given
 * company+year combination.  Returned by SessionService.loadSessionData()
 * and applied to AppSession by MainMenuController.pushToAppSession().
 */
public record SessionData(
    int       faTaxYrEndMth,       // from CPCOYCO.fa_tax_yr_end_mth (default 6)
    LocalDate yrStartDate,          // from GLDATES.yr_start_date
    LocalDate yrEndDate,            // from GLDATES.yr_end_date
    String    batchControlFlag      // from CPCOYCO.fa_batch_control_flag (default "Y")
) {}
