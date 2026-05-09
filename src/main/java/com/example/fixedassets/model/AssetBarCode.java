package com.example.fixedassets.model;

/**
 * One FAASBAR bar code record.
 * PK: company_no + asset_no + bar_code
 */
public record AssetBarCode(int companyNo, String assetNo, String barCode) {}
