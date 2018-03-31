package com.dwe.dealwitheat.restaurant.model;

import com.dwe.dealwitheat.commons.Response;

public class AdminInfoResponse extends Response {

    private String email;
    private String name;
    private String address;

    public AdminInfoResponse() {
    }

    public AdminInfoResponse(String email, String name, String address) {
        this.email = email;
        this.name = name;
        this.address = address;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }
}
