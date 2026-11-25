import axios from 'axios';
import { getStore, removeStore } from './storage';
import { router } from '../router/index';
import { Message } from 'view-design';
import Cookies from 'js-cookie';
let base = '/zwz';

/**
 * 对象转 application/x-www-form-urlencoded
 * 原来 postRequest / putRequest / postNoAuthRequest 各复制了一份，抽出来统一维护
 */
const toUrlEncoded = (data) => {
    if (!data) {
        return '';
    }
    let ret = '';
    for (let it in data) {
        ret += encodeURIComponent(it) + '=' + encodeURIComponent(data[it]) + '&';
    }
    return ret.substring(0, ret.length - 1);
};

/**
 * 统一错误提示，避免 message 为 null/undefined 时弹出空白提示
 */
const showErrorMessage = (message, fallback) => {
    Message.error(message || fallback || '未知错误');
};

axios.defaults.timeout = 15000;
axios.interceptors.request.use(config => {
    return config;
}, err => {
    Message.error('请求超时');
    return Promise.resolve(err);
});

axios.interceptors.response.use(response => {
    const data = response.data;

    // 文件流等非标准响应直接返回
    if (!data || typeof data !== 'object') {
        return data;
    }

    switch (data.code) {
        case 401:
            // 清干净本地登录态：Cookie 存“已登录”标记，localStorage 存 Token 与用户信息
            Cookies.remove('userInfo');
            removeStore('accessToken');
            removeStore('roles');
            removeStore('userInfo');
            if (router.history.current.name != "login") {
                showErrorMessage(data.message, "未知错误，请重新登录");
                router.push('/login');
            }
            break;
        case 403:
            showErrorMessage(data.message, "未知错误");
            break;
        case 500:
            showErrorMessage(data.message, "未知错误");
            break;
        default:
            break;
    }

    return data;
}, (err) => {
    Message.error(err.toString());
    return Promise.resolve(err);
});

export const getRequest = (url, params) => {
    let accessToken = getStore('accessToken');
    return axios({
        method: 'get',
        url: `${base}${url}`,
        params: params,
        headers: {
            'accessToken': accessToken
        }
    });
};

export const postRequest = (url, params) => {
    let accessToken = getStore("accessToken");
    return axios({
        method: 'post',
        url: `${base}${url}`,
        data: params,
        transformRequest: [toUrlEncoded],
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
            'accessToken': accessToken
        }
    });
};

export const putRequest = (url, params) => {
    let accessToken = getStore("accessToken");
    return axios({
        method: 'put',
        url: `${base}${url}`,
        data: params,
        transformRequest: [toUrlEncoded],
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
            'accessToken': accessToken
        }
    });
};

export const postBodyRequest = (url, params) => {
    let accessToken = getStore('accessToken');
    return axios({
        method: 'post',
        url: `${base}${url}`,
        data: params,
        headers: {
            'accessToken': accessToken
        }
    });
};

export const getNoAuthRequest = (url, params) => {
    return axios({
        method: 'get',
        url: `${base}${url}`,
        params: params
    });
};

export const postNoAuthRequest = (url, params) => {
    return axios({
        method: 'post',
        url: `${base}${url}`,
        data: params,
        transformRequest: [toUrlEncoded],
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded'
        }
    });
};
