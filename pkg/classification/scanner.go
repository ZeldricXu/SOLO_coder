package classification

import (
	"context"
	"sync"

	"github.com/solocoder/session136/pkg/common/interfaces"
)

type DataScanner interface {
	Scan(ctx context.Context, data []map[string]interface{}) ([]*interfaces.ClassificationResult, error)
}

type DefaultDataScanner struct {
	classifier    DataClassifier
	policyApplier PolicyApplier
}

func NewDefaultDataScanner(classifier DataClassifier, policyApplier PolicyApplier) *DefaultDataScanner {
	return &DefaultDataScanner{
		classifier:    classifier,
		policyApplier: policyApplier,
	}
}

func (s *DefaultDataScanner) Scan(ctx context.Context, data []map[string]interface{}) ([]*interfaces.ClassificationResult, error) {
	results := make([]*interfaces.ClassificationResult, len(data))
	var wg sync.WaitGroup
	errChan := make(chan error, len(data))

	for i, item := range data {
		wg.Add(1)
		go func(idx int, d map[string]interface{}) {
			defer wg.Done()

			result, err := s.classifier.Classify(ctx, d)
			if err != nil {
				errChan <- err
				return
			}

			if err := s.policyApplier.ApplyPolicy(ctx, result); err != nil {
				errChan <- err
				return
			}

			results[idx] = result
		}(i, item)
	}

	wg.Wait()
	close(errChan)

	if len(errChan) > 0 {
		return nil, <-errChan
	}

	return results, nil
}
