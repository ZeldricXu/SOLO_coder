package channels

import (
	"sync"
	"testing"
	"time"
)

func TestSMSChannel_ChannelType(t *testing.T) {
	channel := NewSMSChannel("aliyun", "test_api_key", "测试签名", "SMS_123456")
	
	if channel.GetChannelType() != "sms" {
		t.Errorf("Expected channel type 'sms', got '%s'", channel.GetChannelType())
	}
}

func TestSMSChannel_Send_Success(t *testing.T) {
	channel := NewSMSChannel("aliyun", "test_api_key", "测试签名", "SMS_123456")
	
	receiver := "13800138000"
	content := "您的订单已成功提交"
	
	result, err := channel.Send(receiver, content, "")
	
	if err != nil {
		t.Errorf("Expected no error, got: %v", err)
	}
	
	if result == nil {
		t.Fatal("Expected result, got nil")
	}
	
	if !result.Success {
		t.Errorf("Expected success, got failed with message: %s", result.Message)
	}
	
	if result.Error != nil {
		t.Errorf("Expected no error in result, got: %v", result.Error)
	}
}

func TestSMSChannel_Send_EmptyReceiver(t *testing.T) {
	channel := NewSMSChannel("aliyun", "test_api_key", "测试签名", "SMS_123456")
	
	result, err := channel.Send("", "测试内容", "")
	
	if err != nil {
		t.Errorf("Expected no error, got: %v", err)
	}
	
	if result == nil {
		t.Fatal("Expected result, got nil")
	}
	
	if result.Success {
		t.Error("Expected failure for empty receiver, got success")
	}
	
	if result.Message != "receiver is empty" {
		t.Errorf("Expected error message 'receiver is empty', got: %s", result.Message)
	}
}

func TestSMSChannel_Send_InvalidPhoneFormat(t *testing.T) {
	channel := NewSMSChannel("aliyun", "test_api_key", "测试签名", "SMS_123456")
	
	invalidPhones := []string{
		"1234567890",
		"123456789012",
		"abcdefghijk",
		"",
	}
	
	for _, phone := range invalidPhones {
		t.Run("phone_"+phone, func(t *testing.T) {
			result, _ := channel.Send(phone, "测试内容", "")
			
			if result.Success {
				t.Errorf("Expected failure for phone '%s', got success", phone)
			}
		})
	}
}

func TestSMSChannel_Send_ValidPhoneFormats(t *testing.T) {
	channel := NewSMSChannel("aliyun", "test_api_key", "测试签名", "SMS_123456")
	
	validPhones := []string{
		"13800138000",
		"13912345678",
		"18600001111",
	}
	
	for _, phone := range validPhones {
		t.Run("phone_"+phone, func(t *testing.T) {
			result, err := channel.Send(phone, "测试内容", "")
			
			if err != nil {
				t.Errorf("Phone %s: Expected no error, got: %v", phone, err)
			}
			
			if !result.Success {
				t.Errorf("Phone %s: Expected success, got failure: %s", phone, result.Message)
			}
		})
	}
}

func TestSMSChannel_Send_Concurrent(t *testing.T) {
	channel := NewSMSChannel("aliyun", "test_api_key", "测试签名", "SMS_123456")
	
	concurrency := 50
	var wg sync.WaitGroup
	var successCount int
	var failCount int
	var mu sync.Mutex
	
	startTime := time.Now()
	
	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			phone := "138" + padZero(idx, 8)
			result, _ := channel.Send(phone, "测试短信内容"+padZero(idx, 3), "")
			
			mu.Lock()
			if result.Success {
				successCount++
			} else {
				failCount++
			}
			mu.Unlock()
		}(i)
	}
	
	wg.Wait()
	
	elapsed := time.Since(startTime)
	
	if successCount != concurrency {
		t.Errorf("Expected %d successful sends, got %d success, %d failed", concurrency, successCount, failCount)
	}
	
	expectedMinTime := time.Duration(concurrency) * 50 * time.Millisecond
	maxExpectedTime := time.Duration(concurrency) * 200 * time.Millisecond
	
	t.Logf("Concurrent SMS test: %d goroutines, elapsed: %v", concurrency, elapsed)
	
	if elapsed > maxExpectedTime {
		t.Errorf("Concurrent sending too slow: elapsed %v, expected max %v", elapsed, maxExpectedTime)
	}
	
	_ = expectedMinTime
}

func TestSMSChannel_Send_ContentVariations(t *testing.T) {
	channel := NewSMSChannel("aliyun", "test_api_key", "测试签名", "SMS_123456")
	
	testCases := []struct {
		name    string
		content string
		wantErr bool
	}{
		{
			name:    "short content",
			content: "您好",
			wantErr: false,
		},
		{
			name:    "long content",
			content: "这是一条很长的测试短信内容，用于测试短信渠道是否能够正确处理较长的消息内容。短信通常有字数限制，但在测试环境中我们需要确保各种长度的内容都能够被正确处理。",
			wantErr: false,
		},
		{
			name:    "special characters",
			content: "测试特殊字符：!@#$%^&*()_+-=[]{}|;':\",./<>?中文测试",
			wantErr: false,
		},
		{
			name:    "empty content",
			content: "",
			wantErr: false,
		},
	}
	
	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			result, err := channel.Send("13800138000", tc.content, "")
			
			if tc.wantErr {
				if err == nil && result.Success {
					t.Error("Expected error, got success")
				}
			} else {
				if err != nil {
					t.Errorf("Expected no error, got: %v", err)
				}
				if !result.Success {
					t.Errorf("Expected success, got: %s", result.Message)
				}
			}
		})
	}
}

func TestSMSChannel_Configuration(t *testing.T) {
	testCases := []struct {
		name         string
		provider     string
		apiKey       string
		signName     string
		templateCode string
	}{
		{
			name:         "aliyun config",
			provider:     "aliyun",
			apiKey:       "LTAI4Fvxyz123",
			signName:     "系统通知",
			templateCode: "SMS_123456789",
		},
		{
			name:         "tencent config",
			provider:     "tencent",
			apiKey:       "AKID123456",
			signName:     "腾讯云",
			templateCode: "12345",
		},
	}
	
	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			channel := NewSMSChannel(tc.provider, tc.apiKey, tc.signName, tc.templateCode)
			
			if channel == nil {
				t.Fatal("Expected channel instance, got nil")
			}
			
			if channel.GetChannelType() != "sms" {
				t.Errorf("Expected channel type 'sms', got '%s'", channel.GetChannelType())
			}
			
			result, err := channel.Send("13800138000", "测试内容", "")
			
			if err != nil {
				t.Errorf("Expected no error, got: %v", err)
			}
			
			if !result.Success {
				t.Errorf("Expected success, got: %s", result.Message)
			}
		})
	}
}

func padZero(num int, width int) string {
	result := ""
	for i := 0; i < width; i++ {
		digit := num % 10
		result = string(rune('0'+digit)) + result
		num = num / 10
	}
	return result
}
