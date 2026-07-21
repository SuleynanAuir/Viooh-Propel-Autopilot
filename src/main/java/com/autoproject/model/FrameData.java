package com.autoproject.model;

public class FrameData {
    private String geom;
    private String ssp;
    private String market;
    private String vioohId;
    private String assetUuid;
    private String visualUnitCode;
    private String addressThoroughfare;
    private String addressAdministrativeArea;
    private String addressLocality;
    private String addressPostalCode;
    private String addressCountry;
    private String addressIso3CountryCode;
    private Double latitude;
    private Double longitude;
    private String productFormatName;
    private Integer digitalSpecWidth;
    private Integer digitalSpecHeight;
    private String widthXHeight;
    private String aspectRatio;
    private String digitalSpecMotionType;
    private Integer digitalSpecFps;
    private Integer digitalSpecRotation;
    private Integer slotDuration;
    private Integer venueTaxonomyId;
    private String venueTaxonomyValue;
    private String frameImagePath;
    private Double impressions;
    private Double floorCpm;
    private Double effectiveFloorCpm;
    private String mediaOwnerCurrency;
    private String vioohSelectOptin;
    private String closestPoi;
    private String distanceToClosestPoi;
    private String iata;
    private Double scoreP;

    public FrameData() {
    }

    //Getter and Setter methods

    public String getGeom() {
        return geom;
    }

    public void setGeom(String geom) {
        this.geom = geom;
    }

    public String getSsp() {
        return ssp;
    }

    public void setSsp(String ssp) {
        this.ssp = ssp;
    }

    public String getMarket() {
        return market;
    }

    public void setMarket(String market) {
        this.market = market;
    }

    public String getVioohId() {
        return vioohId;
    }

    public void setVioohId(String vioohId) {
        this.vioohId = vioohId;
    }

    public String getAssetUuid() {
        return assetUuid;
    }

    public void setAssetUuid(String assetUuid) {
        this.assetUuid = assetUuid;
    }

    public String getVisualUnitCode() {
        return visualUnitCode;
    }

    public void setVisualUnitCode(String visualUnitCode) {
        this.visualUnitCode = visualUnitCode;
    }

    public String getAddressThoroughfare() {
        return addressThoroughfare;
    }

    public void setAddressThoroughfare(String addressThoroughfare) {
        this.addressThoroughfare = addressThoroughfare;
    }

    public String getAddressAdministrativeArea() {
        return addressAdministrativeArea;
    }

    public void setAddressAdministrativeArea(String addressAdministrativeArea) {
        this.addressAdministrativeArea = addressAdministrativeArea;
    }

    public String getAddressLocality() {
        return addressLocality;
    }

    public void setAddressLocality(String addressLocality) {
        this.addressLocality = addressLocality;
    }

    public String getAddressPostalCode() {
        return addressPostalCode;
    }

    public void setAddressPostalCode(String addressPostalCode) {
        this.addressPostalCode = addressPostalCode;
    }

    public String getAddressCountry() {
        return addressCountry;
    }

    public void setAddressCountry(String addressCountry) {
        this.addressCountry = addressCountry;
    }

    public String getAddressIso3CountryCode() {
        return addressIso3CountryCode;
    }

    public void setAddressIso3CountryCode(String addressIso3CountryCode) {
        this.addressIso3CountryCode = addressIso3CountryCode;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public String getProductFormatName() {
        return productFormatName;
    }

    public void setProductFormatName(String productFormatName) {
        this.productFormatName = productFormatName;
    }

    public Integer getDigitalSpecWidth() {
        return digitalSpecWidth;
    }

    public void setDigitalSpecWidth(Integer digitalSpecWidth) {
        this.digitalSpecWidth = digitalSpecWidth;
    }

    public Integer getDigitalSpecHeight() {
        return digitalSpecHeight;
    }

    public void setDigitalSpecHeight(Integer digitalSpecHeight) {
        this.digitalSpecHeight = digitalSpecHeight;
    }

    public String getWidthXHeight() {
        return widthXHeight;
    }

    public void setWidthXHeight(String widthXHeight) {
        this.widthXHeight = widthXHeight;
    }

    public String getAspectRatio() {
        return aspectRatio;
    }

    public void setAspectRatio(String aspectRatio) {
        this.aspectRatio = aspectRatio;
    }

    public String getDigitalSpecMotionType() {
        return digitalSpecMotionType;
    }

    public void setDigitalSpecMotionType(String digitalSpecMotionType) {
        this.digitalSpecMotionType = digitalSpecMotionType;
    }

    public Integer getDigitalSpecFps() {
        return digitalSpecFps;
    }

    public void setDigitalSpecFps(Integer digitalSpecFps) {
        this.digitalSpecFps = digitalSpecFps;
    }

    public Integer getDigitalSpecRotation() {
        return digitalSpecRotation;
    }

    public void setDigitalSpecRotation(Integer digitalSpecRotation) {
        this.digitalSpecRotation = digitalSpecRotation;
    }

    public Integer getSlotDuration() {
        return slotDuration;
    }

    public void setSlotDuration(Integer slotDuration) {
        this.slotDuration = slotDuration;
    }

    public Integer getVenueTaxonomyId() {
        return venueTaxonomyId;
    }

    public void setVenueTaxonomyId(Integer venueTaxonomyId) {
        this.venueTaxonomyId = venueTaxonomyId;
    }

    public String getVenueTaxonomyValue() {
        return venueTaxonomyValue;
    }

    public void setVenueTaxonomyValue(String venueTaxonomyValue) {
        this.venueTaxonomyValue = venueTaxonomyValue;
    }

    public String getFrameImagePath() {
        return frameImagePath;
    }

    public void setFrameImagePath(String frameImagePath) {
        this.frameImagePath = frameImagePath;
    }

    public Double getImpressions() {
        return impressions;
    }

    public void setImpressions(Double impressions) {
        this.impressions = impressions;
    }

    public Double getFloorCpm() {
        return floorCpm;
    }

    public void setFloorCpm(Double floorCpm) {
        this.floorCpm = floorCpm;
    }

    public Double getEffectiveFloorCpm() {
        return effectiveFloorCpm != null ? effectiveFloorCpm : floorCpm;
    }

    public void setEffectiveFloorCpm(Double effectiveFloorCpm) {
        this.effectiveFloorCpm = effectiveFloorCpm;
    }

    public String getMediaOwnerCurrency() {
        return mediaOwnerCurrency;
    }

    public void setMediaOwnerCurrency(String mediaOwnerCurrency) {
        this.mediaOwnerCurrency = mediaOwnerCurrency;
    }

    public String getVioohSelectOptin() {
        return vioohSelectOptin;
    }

    public void setVioohSelectOptin(String vioohSelectOptin) {
        this.vioohSelectOptin = vioohSelectOptin;
    }

    public String getClosestPoi() {
        return closestPoi;
    }

    public void setClosestPoi(String closestPoi) {
        this.closestPoi = closestPoi;
    }

    public String getDistanceToClosestPoi() {
        return distanceToClosestPoi;
    }

    public void setDistanceToClosestPoi(String distanceToClosestPoi) {
        this.distanceToClosestPoi = distanceToClosestPoi;
    }

    public String getIata() {
        return iata;
    }

    public void setIata(String iata) {
        this.iata = iata;
    }

    public Double getScoreP() {
        return scoreP;
    }

    public void setScoreP(Double scoreP) {
        this.scoreP = scoreP;
    }
}
