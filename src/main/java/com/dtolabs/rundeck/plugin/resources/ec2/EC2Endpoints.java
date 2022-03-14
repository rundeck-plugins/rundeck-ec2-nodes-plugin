package com.dtolabs.rundeck.plugin.resources.ec2;

import com.amazonaws.services.ec2.model.DescribeRegionsResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class EC2Endpoints {

    public static String[] all_endpoints() {

        String[] endpoints = {
                "https://ec2.us-east-2.amazonaws.com",
                "https://ec2.us-east-1.amazonaws.com",
                "https://ec2.us-west-1.amazonaws.com",
                "https://ec2.us-west-2.amazonaws.com",
                "https://ec2.af-south-1.amazonaws.com",
                "https://ec2.ap-east-1.amazonaws.com",
                "https://ec2.ap-southeast-3.amazonaws.com",
                "https://ec2.ap-south-1.amazonaws.com",
                "https://ec2.ap-northeast-3.amazonaws.com",
                "https://ec2.ap-northeast-2.amazonaws.com",
                "https://ec2.ap-southeast-1.amazonaws.com",
                "https://ec2.ap-southeast-2.amazonaws.com",
                "https://ec2.ap-northeast-1.amazonaws.com",
                "https://ec2.ca-central-1.amazonaws.com",
                "https://ec2.eu-central-1.amazonaws.com",
                "https://ec2.eu-west-1.amazonaws.com",
                "https://ec2.eu-west-2.amazonaws.com",
                "https://ec2.eu-south-1.amazonaws.com",
                "https://ec2.eu-west-3.amazonaws.com",
                "https://ec2.eu-north-1.amazonaws.com",
                "https://ec2.me-south-1.amazonaws.com",
                "https://ec2.sa-east-1.amazonaws.com"
        };

        return endpoints;

    }
}
