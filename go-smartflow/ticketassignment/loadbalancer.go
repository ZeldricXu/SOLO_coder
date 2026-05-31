package ticketassignment

import "time"

type LoadBalancer struct{}

func NewLoadBalancer() *LoadBalancer {
	return &LoadBalancer{}
}

func (lb *LoadBalancer) CalculateLoadScore(employee Employee, config AssignmentConfig) float64 {
	if employee.MaxLoad <= 0 {
		return 0
	}

	loadRatio := float64(employee.CurrentLoad) / float64(employee.MaxLoad)
	if loadRatio >= 1.0 {
		return 0
	}

	return (1.0 - loadRatio) * config.LoadBalanceWeight
}

func (lb *LoadBalancer) GetLoadRatio(employee Employee) float64 {
	if employee.MaxLoad <= 0 {
		return 1.0
	}
	return float64(employee.CurrentLoad) / float64(employee.MaxLoad)
}

func (lb *LoadBalancer) CanAcceptWork(employee Employee) bool {
	if !employee.IsAvailable {
		return false
	}
	if employee.MaxLoad <= 0 {
		return false
	}
	return employee.CurrentLoad < employee.MaxLoad
}

func (lb *LoadBalancer) CalculateIdleBonus(employee Employee, maxIdleTime time.Duration) float64 {
	idleTime := time.Since(employee.LastAssigned)
	if idleTime > maxIdleTime {
		return 0.1
	}
	return 0
}
