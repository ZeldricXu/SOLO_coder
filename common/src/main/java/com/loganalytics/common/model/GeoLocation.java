package com.loganalytics.common.model;

public class GeoLocation {
    private String ip;
    private String country;
    private String region;
    private String city;
    private double latitude;
    private double longitude;
    private String timezone;
    private String isp;
    private String asn;

    public GeoLocation() {}

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public String getIsp() { return isp; }
    public void setIsp(String isp) { this.isp = isp; }

    public String getAsn() { return asn; }
    public void setAsn(String asn) { this.asn = asn; }
}
